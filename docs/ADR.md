# Architecture Decision Record (ADR)

## SmartDialer: Telephony Pacing & Safety Architecture

**Status:** Accepted  
**Date:** 2026-10-27  
**Author:** Senior Backend Systems Engineer  

---

### Context & Problem Statement

Collections organizations rely on outbound telephony to connect agents with borrowers.
- **Progressive Dialing:** Places at most 1 call per available agent. Deterministically prevents abandoned calls (where a borrower answers but no agent is free), but leads to significant agent idle time because 70–80% of outbound calls go unanswered or connect to voicemail.
- **Predictive Dialing:** Anticipates agent availability and borrower answer probabilities to dial ahead. While it substantially increases agent utilization, it introduces the critical risk of **abandoned connected calls** (a major compliance violation subject to strict regulatory caps under FTC/FCC/Ofcom rules).

Our objective is to design and implement an industrial-strength prototype that combines the high utilization of predictive pacing with the deterministic safety invariants of progressive dialing.

---

### Key Architectural Decisions

#### 1. Architecture Style: Modular Monolith over Distributed Microservices
- **Decision:** Build the entire system as a cleanly modularized Spring Boot monolith with domain-driven boundaries (Campaign, Queue, Agent Lifecycle, Pacing, Safety, Allocator, Telecom, Recovery) sharing a relational datastore (PostgreSQL/H2).
- **Why:** Telephony call allocation requires sub-10ms transactional coordination between agent reservation and borrower queue locks. Splitting this into multiple microservices across network boundaries introduces distributed transaction overhead (Two-Phase Commit / Sagas), network latency jitter, partial network partition risks, and complex distributed consensus (Raft/Paxos).
- **Trade-off:** A monolith scales vertically or horizontally with a shared database, but horizontal worker nodes must contend on database row locks. At very large scale (>10,000 agents), sharding by campaign or call center cluster is required.

#### 2. Concurrency & Atomic State Transitions: Database CAS with Version Tracking
- **Decision:** Reject in-memory locks, JVM-local flags, or uncoordinated Redis caches as sources of truth for agent/borrower reservations. Use conditional database Compare-And-Swap (CAS) updates:
  ```sql
  UPDATE agents 
  SET state = 'RESERVED', reservation_expires_at = :expiresAt, version = version + 1
  WHERE id = :id AND state = 'AVAILABLE' AND version = :expectedVersion;
  ```
- **Why:** If multiple worker instances process the same campaign queue, local JVM mutexes (`synchronized`, `ReentrantLock`) do not cross process boundaries. Atomic database CAS queries guarantee that exactly one worker succeeds in claiming an agent or borrower without heavy database table-level locks (`SELECT FOR UPDATE`).
- **What it makes harder:** Requires careful entity version reloading in long-running services to avoid `ObjectOptimisticLockingFailure` on subsequent writes.

#### 3. Dual-Write Safety: Intent-First Design for Worker Crash & Telecom Recovery
- **Decision:** Never initiate an external network call to a telecom provider within an uncommitted database transaction or without an audit log. Implement a two-phase `CallIntent`:
  1. Phase 1: Atomically claim agent & borrower, write a persistent `CallIntent` with a unique idempotency key and `dispatched = false`.
  2. Phase 2: Dispatch external HTTP/SIP call to Telecom Provider. On confirmation, mark `dispatched = true` and transition call to `INITIATED`.
- **Why:** If the worker crashes immediately after reserving the agent or while waiting for provider HTTP response, the database intent record allows the background `RecoveryAndRetryManager` to reconcile the call, release stranded agents/borrowers, and avoid blind, duplicate outbound calls.
- **Trade-off:** Adds an extra database write before initiating an outbound call, adding ~1–2ms of database write latency.

#### 4. Unbypassable Gatekeeper: Fail-Closed Safety Controller
- **Decision:** Enforce the strict architecture pipeline:
  ```
  Campaign -> Pacing Engine -> Safety Controller -> Call Allocator -> Telecom Provider
  ```
  The Pacing Engine has **zero direct access** to telecom interfaces. The Safety Controller is the unbypassable authority that evaluates every proposed dial count and can `APPROVE`, `REDUCE`, `REJECT`, or `FALLBACK_PROGRESSIVE`.
- **Policy:** **Fail-Closed.** If provider health cannot be verified, or failure rates exceed the circuit-breaker threshold (25%), or available agents drop to 0, all outbound calls are strictly rejected.
- **Trade-off:** During moments of telecom provider instability, dialing throughput is intentionally throttled or halted to protect regulatory compliance.

#### 5. Statistical Answer Rate Smoothing: Bayesian m-Estimate
- **Decision:** Calculate the smoothed answer rate using Bayesian m-estimate smoothing:
  $$P_{\text{smoothed}} = \frac{\text{observed\_answers} + m \cdot P_{\text{prior}}}{\text{total\_attempts} + m}$$
  where $m$ is the configurable minimum sample size (e.g., 20) and $P_{\text{prior}}$ is the campaign baseline default rate (e.g., 0.30).
- **Why:** In cold-start situations or during small sample sizes, empirical answer rates fluctuate wildly (e.g., 1 answer out of 1 attempt = 100%, which would cause the pacing engine to propose reckless dial surges). The Bayesian smoother regresses toward the conservative prior when data is scarce and naturally converges to empirical reality as observations accumulate.

#### 6. Idempotency & Persistent Event Deduplication
- **Decision:** Store every incoming provider event ID in a persistent `call_event_logs` table with a unique database index. Before applying any state transition, check for event existence.
- **Why:** Telecom providers (Twilio, Plivo, Asterisk) deliver webhooks with at-least-once semantics; duplicate `ANSWERED` or `RINGING` webhooks are routine under carrier retries. Persistent deduplication ensures that duplicate webhooks are safe no-ops and prevents double-counting metrics or triggering erroneous agent transitions.

---

### Failure Scenarios & Mitigations

| Failure Scenario | Threat | Mitigation in Prototype |
| :--- | :--- | :--- |
| **Worker Crash during setup** | Orphaned reservations, agents held hostage | `RecoveryAndRetryManager` scans `CallIntent` with expired leases and releases agents/borrowers back to queue |
| **Provider Network Outage** | Dial storms during carrier 504 timeouts | Safety Controller circuit breaker opens when failure rate exceeds 25%, halting new requests |
| **Agent Disconnects / Drops** | Answered call left with no agent | Safety Controller re-checks real-time agent availability from DB immediately prior to dispatch |
| **Out-of-Order Webhooks** | `COMPLETED` webhook arrives before `ANSWERED` | `CallStateMachine` checks previous state, backfills missing timestamps, and transitions cleanly to `COMPLETED` |
| **Answer-Rate Collapse (70% &rarr; 10%)** | Overdialing causes abandoned calls | Smoothed answer rate rapidly adjusts, exposure checks reduce pacing, and exposure caps clamp maximum in-flight calls |
