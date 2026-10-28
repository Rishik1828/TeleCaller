# SmartDialer: High-Reliability Progressive & Predictive Telephony Engine

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Build Status](https://img.shields.io/badge/Build-Passing%20(23%2F23%20Tests)-success.svg)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)]()

> A production-grade **SmartDialer prototype** built with Java 21, Spring Boot 3.x, JPA, and an unbypassable fail-closed Safety Controller. Maximizes collections agent utilization through rule-based predictive pacing while deterministically enforcing progressive call safety invariants to eliminate compliance-violating abandoned calls.

---

## Table of Contents
1. [Core Features & Architecture](#core-features--architecture)
2. [Architecture Diagrams (Mermaid)](#architecture-diagrams)
3. [State Machines (Agent & Call)](#state-machines)
4. [Predictive Pacing Formula & Worked Example](#predictive-pacing-formula--worked-example)
5. [Prerequisites & Quickstart](#prerequisites--quickstart)
6. [Web Dashboard & Simulation Studio](#web-dashboard--simulation-studio)
7. [REST API Documentation & cURL Examples](#rest-api-documentation)
8. [Automated Testing Suite (23 Tests)](#automated-testing-suite)
9. [Load Testing & Benchmark Results](#load-testing--benchmark-results)
10. [Scaling Bottlenecks: 100 → 1,000 → 10,000 Agents](#scaling-bottlenecks)
11. [Final Interview Question: Predictive Utilization + Progressive Safety](#final-interview-question)
12. [Interview Defense & Common Q&A](#interview-defense--common-qa)

---

## Core Features & Architecture

The system is constructed as a **modular monolith** with 12 distinct components and strict transactional boundaries:

1. **Campaign Manager:** Controls campaign lifecycles (`CREATED`, `RUNNING`, `PAUSED`, `COMPLETED`), target pacing parameters, and mode selection (`PROGRESSIVE` vs `PREDICTIVE`).
2. **Borrower Queue:** Priority-ordered queue with retry management, exponential backoff (30s &times; 2<sup>attempts</sup>), and optimistic versioning.
3. **Agent State Manager:** Manages explicit agent states (`OFFLINE`, `AVAILABLE`, `RESERVED`, `DIALING`, `CONNECTED`, `WRAP_UP`, `PAUSED`) with atomic CAS updates.
4. **Progressive Dialer:** Deterministic 1:1 dialer ensuring no more calls than available agents are ever initiated.
5. **Predictive Pacing Engine:** Statistical, rule-based turnover estimator computing future available capacity based on active calls, call setup horizon, and Bayesian-smoothed answer rates.
6. **Safety Controller (HIGHEST PRIORITY):** The final, unbypassable authority. Enforces capacity caps, ringing exposure limits, and provider circuit breakers. Operates on a **fail-closed** policy.
7. **Call Allocator:** Atomically claims borrower and agent resources, persists a two-phase `CallIntent` before telecom invocation, and manages provider dispatch.
8. **Telecom Provider Interface:** Pluggable telephony abstraction (`initiateCall`, `cancelCall`, `getProviderHealth`, `registerEventListener`).
9. **Two Mock Telecom Providers:**
   - **MockProviderA:** Fast (50–100ms latency), 98% reliability, normal event order.
   - **MockProviderB:** Slower (400–800ms latency), timeout simulation, duplicate event injection, and out-of-order delivery.
10. **Provider Event Processor:** Centralized state transition service with persistent event deduplication (`call_event_logs`) to handle at-least-once carrier delivery and out-of-order sequence reconciliation.
11. **Recovery and Retry Manager:** Background scanner recovering stranded agent leases, timed-out active calls, and worker crashes without initiating duplicate calls.
12. **Simulation & Metrics Service:** Reproducible simulator for Scenarios A–D with real-time telemetry on agent utilization, abandoned calls, and safety decisions.

---

## Architecture Diagrams

### 1. Pacing & Safety Pipeline Flow

```mermaid
flowchart LR
    subgraph Campaign_Layer [Campaign & Pacing]
        C[Campaign Manager] -->|Trigger Cycle| P[Predictive Pacing Engine]
        P -->|1. Propose N Calls + Explainability| SC
    end

    subgraph Safety_Layer [Safety Gatekeeper]
        SC[Safety Controller] -->|2. Verify Agent Capacity & Provider Health| CA
    end

    subgraph Allocation_Layer [Call Allocator & Database]
        CA[Call Allocator] -->|3. Atomic CAS Reservation| DB[(H2 / PostgreSQL)]
        CA -->|4. Persist Two-Phase Intent| CI[Call Intent Log]
        CA -->|5. Initiate Call| TP
    end

    subgraph Telecom_Layer [Telecom Providers]
        TP{Telecom Provider}
        TP -->|Fast / Low Jitter| PA[MockProvider A]
        TP -->|Jitter / Duplicate Events| PB[MockProvider B]
    end

    subgraph Webhook_Ingress [Event Processor]
        PA -.->|Async Webhooks| PEP[Provider Event Processor]
        PB -.->|Async Webhooks| PEP
        PEP -->|Idempotent Deduplication| EL[(call_event_logs)]
        PEP -->|Reconcile State| CSM[Call & Agent State Machines]
    end
```

> **Critical Invariant:** The Pacing Engine **never** interacts with the Telecom Provider directly. Every outbound attempt must be validated by the Safety Controller.

---

## State Machines

### Agent State Machine

```mermaid
stateDiagram-v2
    [*] --> OFFLINE
    OFFLINE --> AVAILABLE : Sign On
    AVAILABLE --> RESERVED : Atomic CAS Lease (Call Allocator)
    AVAILABLE --> PAUSED : Break / Inactive
    AVAILABLE --> OFFLINE : Sign Off
    RESERVED --> DIALING : Outbound Dispatched to Telecom
    RESERVED --> AVAILABLE : Setup Failed / Timeout
    DIALING --> CONNECTED : Borrower Answered
    DIALING --> AVAILABLE : Call Failed (No Answer / Busy)
    CONNECTED --> WRAP_UP : Call Completed
    WRAP_UP --> AVAILABLE : Wrap-up Complete
    PAUSED --> AVAILABLE : Resume Ready
    PAUSED --> OFFLINE : Sign Off
```

- **Atomic Guard:** Agent state updates execute via conditional Compare-And-Swap:
  `UPDATE agents SET state = 'RESERVED', version = version + 1 WHERE id = :id AND state = 'AVAILABLE' AND version = :expectedVersion`
- **Two Workers Collision:** If two workers race for the same available agent, only one CAS returns `rowsUpdated = 1`. The loser returns `0` and safely backs off.

---

### Call State Machine

```mermaid
stateDiagram-v2
    [*] --> QUEUED
    QUEUED --> RESERVED : Agent & Borrower Claimed
    RESERVED --> INITIATED : Telecom Accepted
    INITIATED --> RINGING : Ringing Tone Detected
    INITIATED --> COMPLETED : Out-of-Order Reconciled
    RINGING --> ANSWERED : Borrower Picked Up
    ANSWERED --> CONNECTED : Audio Bridged to Agent
    CONNECTED --> COMPLETED : Normal Hangup (Terminal)
    
    QUEUED --> CANCELLED : Campaign Stopped
    RESERVED --> FAILED : Provider Rejection
    INITIATED --> FAILED : Network Timeout
    RINGING --> FAILED : Busy / No Answer
    ANSWERED --> FAILED : Audio Bridge Failed
    
    COMPLETED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
```

- **Terminal Immutability:** Once in `COMPLETED`, `FAILED`, or `CANCELLED`, late webhooks are rejected without corrupting the terminal state.
- **Out-of-Order Reconciliation:** If a provider delivers `COMPLETED` before `ANSWERED`, the state machine backfills the answered timestamp and completes cleanly.

---

## Predictive Pacing Formula & Worked Example

### 1. Mathematical Formulation

Let:
- $A_{\text{avail}}$ = Count of currently available agents
- $C_{\text{conn}}$ = Count of currently connected calls
- $R_{\text{ring}}$ = Count of in-flight ringing calls (unconfirmed exposure)
- $H$ = Call setup horizon (average time between dial initiation and borrower answer, clamped to $10 \le H \le 25\text{s}$)
- $T_{\text{talk}}$ = Average talk duration in seconds
- $P_{\text{prior}}$ = Configured default answer rate (e.g. 0.30)
- $m$ = Minimum sample size prior weight (e.g. 20)

**Step 1: Bayesian Smoothed Answer Rate**
$$P_{\text{answer}} = \frac{\text{ObservedAnswers} + m \cdot P_{\text{prior}}}{\text{TotalFinishedAttempts} + m}$$

**Step 2: Expected Agent Turnover During Setup Horizon**
$$\text{ExpectedFreedAgents} = C_{\text{conn}} \cdot \left(\frac{H}{T_{\text{talk}}}\right)$$

**Step 3: Net Available Capacity**
$$\text{NetCapacity} = A_{\text{avail}} + \text{ExpectedFreedAgents} - R_{\text{ring}}$$
If $\text{NetCapacity} \le 0$, $\text{ProposedCalls} = 0$.

**Step 4: Raw Dials & Conservative Safety Buffer**
$$\text{RawDials} = \frac{\text{NetCapacity}}{P_{\text{answer}}}$$
$$\text{ProposedCalls} = \lfloor \text{RawDials} \cdot S \rfloor \quad \text{where } S = 0.85 \text{ (85% Safety Buffer)}$$

**Step 5: Telecom Health Discount & Surge Ceiling**
- If provider failure rate $> 15\%$, throttle proposed dials by $\left(1.0 - \frac{\text{FailureRate}}{100}\right)$.
- Max burst ceiling: $\text{ProposedCalls} \le \max(2, 3 \cdot A_{\text{avail}} + 5)$.

---

### 2. Worked Example

Suppose:
- Available Agents $A_{\text{avail}} = 10$
- Connected Calls $C_{\text{conn}} = 20$
- Ringing Calls $R_{\text{ring}} = 2$
- Average Talk Duration $T_{\text{talk}} = 90\text{s}$, Setup Horizon $H = 15\text{s}$
- Historical Data: 35 answers out of 100 attempts, Prior $P_{\text{prior}} = 0.30$, $m = 20$

1. **Smoothed Answer Rate:**
   $$P_{\text{answer}} = \frac{35 + 20 \times 0.30}{100 + 20} = \frac{41}{120} \approx 0.3417 \text{ (34.17\%)}$$
2. **Expected Agent Turnover:**
   $$\text{ExpectedFreed} = 20 \cdot \left(\frac{15}{90}\right) = 20 \cdot 0.1667 \approx 3.33 \text{ agents}$$
3. **Net Available Capacity:**
   $$\text{NetCapacity} = 10 + 3.33 - 2 = 11.33 \text{ agents}$$
4. **Raw Calls Needed:**
   $$\text{RawDials} = \frac{11.33}{0.3417} \approx 33.16$$
5. **Conservative Proposed Volume:**
   $$\text{ProposedCalls} = \lfloor 33.16 \cdot 0.85 \rfloor = \lfloor 28.18 \rfloor = \mathbf{28 \text{ calls}}$$

The Pacing Engine outputs this exact decision with full mathematical explainability to the Safety Controller!

---

## Prerequisites & Quickstart

### Prerequisites
- **JDK 21** or higher (`java -version`)
- **Maven 3.8+** (`mvn -version`)
- Port **8080** available

### Build and Run

```bash
# 1. Clone or navigate to project directory
cd TeleCaller

# 2. Compile and run all automated tests
mvn clean test

# 3. Launch the Spring Boot application
mvn spring-boot:run
```

Once running:
- **Interactive Web UI:** [http://localhost:8080](http://localhost:8080)
- **H2 In-Memory DB Console:** [http://localhost:8080/h2-console](http://localhost:8080/h2-console) (JDBC URL: `jdbc:h2:mem:smartdialer`, user: `sa`, empty password)

---

## Web Dashboard & Simulation Studio

The application includes an interactive glassmorphism Web Console running directly on `http://localhost:8080`:

1. **Live Dialer Console:**
   - Real-time counters for Available Agents, Active Calls, Ringing Exposure, and Abandoned Calls.
   - 1-click Dialing Cycle execution.
   - Live streaming Safety Controller Decision audit log.
   - Interactive Agent table with state toggle (Available &harr; Paused).
2. **Simulation Studio:**
   - 1-click execution of **Scenarios A, B, C, and D**.
   - Configurable agent count, borrower count, cycles, and provider selection.
   - Detailed metric summary cards displaying Agent Utilization %, Initiated, Answered, and Safety approvals.
3. **Resilience & Failures Sandbox:**
   - Worker crash recovery trigger.
   - Duplicate webhook injector (tests idempotent deduplication).
   - Out-of-order event injector (tests state machine backfill).
4. **Architecture & Formulas:**
   - Live architectural documentation and mathematical formulas.

---

## REST API Documentation

### 1. Campaigns

#### Create Campaign
```bash
curl -X POST http://localhost:8080/api/campaigns \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Q4 Collections Campaign",
    "dialingMode": "PREDICTIVE",
    "activeProvider": "MOCK_PROVIDER_A",
    "targetAnswerRate": 0.35
  }'
```

#### Run Dialing Cycle
```bash
curl -X POST http://localhost:8080/api/campaigns/1/dial-cycle
```

*Response:*
```json
{
  "campaignId": 1,
  "mode": "PREDICTIVE",
  "pacingDecision": {
    "proposedCalls": 28,
    "estimatedAnswerRate": 0.342,
    "expectedAgentCapacity": 11.33,
    "safetyMargin": 0.85,
    "explanationReason": "Expected capacity: 11.3 (avail: 10 + freed: 3.3 - ringing: 2). Smoothed P(ans): 34.17%. Raw dials: 33.2. Final proposed calls: 28"
  },
  "safetyDecision": {
    "outcome": "APPROVED",
    "approvedCalls": 28,
    "requestedCalls": 28,
    "reason": "PREDICTIVE APPROVED: 28 calls permitted (available: 10, ringing: 2)"
  },
  "allocationResult": {
    "requestedCalls": 28,
    "successfullyInitiated": 28,
    "failedAllocations": 0,
    "summary": "Allocated 28/28 calls for campaign 1"
  }
}
```

---

### 2. Agents

#### Create Agent
```bash
curl -X POST http://localhost:8080/api/agents \
  -H "Content-Type: application/json" \
  -d '{"agentExternalId": "agent-101", "name": "Sarah Connor", "state": "AVAILABLE"}'
```

#### Update Agent Status
```bash
curl -X PATCH http://localhost:8080/api/agents/1/status \
  -H "Content-Type: application/json" \
  -d '{"state": "PAUSED", "reason": "Break time"}'
```

---

### 3. Simulation Execution

#### Run Simulation Scenario
```bash
curl -X POST http://localhost:8080/api/simulation/run \
  -H "Content-Type: application/json" \
  -d '{
    "scenario": "SCENARIO_B",
    "dialingMode": "PREDICTIVE",
    "providerType": "MOCK_PROVIDER_A",
    "agentCount": 30,
    "borrowerCount": 150,
    "cyclesToRun": 10,
    "cycleDelayMs": 300
  }'
```

#### Get Provider Health
```bash
curl -X GET http://localhost:8080/api/providers/health
```

#### Trigger Background Recovery
```bash
curl -X POST http://localhost:8080/api/recovery/run
```

---

## Automated Testing Suite

The project includes **23 comprehensive automated tests** validating concurrency, state machines, safety gates, and failure recovery:

| Test Class | Scenarios Tested |
| :--- | :--- |
| `AgentStateMachineTest` | Standard lifecycle transitions, illegal transition rejection, pause/offline paths |
| `CallStateMachineTest` | Full call lifecycle, terminal immutability, out-of-order `COMPLETED` timestamp reconciliation, duplicate event idempotent handling |
| `ConcurrentAllocationTest` | **20 concurrent threads racing for 1 agent (exactly 1 wins)**; 20 threads racing for 1 borrower |
| `PredictivePacingEngineTest` | Rule-based turnover calculation, explainability text, ringing saturation rejection, provider failure throttling, Bayesian smoothing convergence |
| `SafetyControllerTest` | Zero available agent hard rejection, progressive 1:1 capacity capping, ringing exposure limits, circuit breaker open on provider outage |
| `TelecomProviderAndEventTest`| Persistent deduplication table drops duplicate webhooks, out-of-order event handling frees agent |
| `RecoveryAndRetryManagerTest` | **Worker crash recovery:** clears undispatched intents and frees agents/borrowers; expired agent reservation lease auto-cleanup |
| `SmartDialerIntegrationTest` | End-to-end Progressive dialing validation; full simulation execution of Scenario B with zero abandoned calls |

Run tests:
```bash
mvn test
```

*Output:*
```
[INFO] Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

## Load Testing & Benchmark Results

Run the included load test scripts:

**PowerShell (Windows):**
```powershell
.\load-test.ps1 -BaseUrl "http://localhost:8080" -Iterations 100 -Concurrency 10
```

**Bash (Linux / macOS):**
```bash
chmod +x load-test.sh
./load-test.sh http://localhost:8080 50
```

### Measured Benchmark Results (Local Test Run)

```
==========================================================
  BENCHMARK RESULTS
==========================================================
  Total Dial Cycles Executed: 100
  Failed Cycles:             0
  Elapsed Time:              3.42 seconds
  Throughput:                29.24 cycles/second
  Allocations Under Load:    ~350 calls/second initiated
  Abandoned Calls:           0 (Invariant Maintained)
==========================================================
```

---

## Scaling Bottlenecks: 100 → 1,000 → 10,000 Agents

When scaling an enterprise outbound dialer, systems do not fail by running out of compute; they fail on **state contention and race conditions**.

### 1. Scaling to 100 Agents
- **Status:** Handled trivially by the current architecture.
- **Characteristics:** Minimal database lock contention. An H2 or PostgreSQL instance with a connection pool of 20 handles the allocation cycles easily in <15ms.

### 2. Scaling to 1,000 Agents
- **What Breaks First:**
  1. **Database Contention on `agents` and `borrowers` tables:** As workers poll for `AVAILABLE` agents and `PENDING` borrowers, table scans and index row locks begin to conflict.
  2. **Webhook Ingress Saturation:** 1,000 agents with a 30% answer rate generates ~3,300 calls in flight, resulting in ~10,000 webhooks per minute hitting the HTTP ingress.
- **Architectural Fix:**
  - **SQL `SKIP LOCKED`:** Transition repository queries from standard updates to `SELECT id FROM agents WHERE state = 'AVAILABLE' FOR UPDATE SKIP LOCKED LIMIT :n`. This prevents concurrent workers from blocking each other on the same candidate rows.
  - **Connection Pooling:** Size HikariCP appropriately with connection pooling and read replicas for reporting metrics.
  - **Asynchronous Webhook Buffering:** Decouple webhook ingress by dumping incoming carrier events into a high-throughput partition queue (Kafka or lightweight distributed log) before persisting to `call_event_logs`.

### 3. Scaling to 10,000 Agents
- **What Breaks First:**
  1. **Single Database Write Bottleneck:** 10,000 concurrent agents handling 60-second calls means 160 calls ending every second and ~500 calls initiating every second. A single relational database cannot sustain atomic updates across 10,000 agent rows without lock serialization delays.
  2. **Telephony Carrier Rate Limits (CPS):** Telecom carriers enforce Call Per Second (CPS) limits (typically 30–100 CPS per trunk). Blasting 500 calls in a single second will trigger SIP `503 Service Unavailable` or `429 Too Many Requests`.
- **Architectural Fix:**
  - **Sharding by Campaign / Call Center Cluster:** Partition campaigns and agent pools across independent database shards (e.g. Shard 1 handles West Coast Collections, Shard 2 handles Auto Loans). State transitions remain strictly local to each shard, eliminating cross-shard transaction coordination.
  - **Distributed CPS Token Bucket:** Implement an in-memory or Redis-backed sliding window token bucket rate limiter directly before the telecom provider dispatch to smooth outbound call placement within carrier CPS limits.

---

## Final Interview Question

### Question:
> *"How would you build a SmartDialer that gets as much of the utilization benefit of predictive dialing as possible, while retaining the deterministic safety characteristics of progressive dialing?"*

### Solution: **The "Dynamic Elastic Leeway" Hybrid Dialing Model**

1. **Deterministic Agent Reservation at Answer Time (Zero-Abandon Invariant):**
   Progressive dialing guarantees safety by reserving the agent *before* dialing. Predictive dialing gains efficiency by dialing *before* the agent is free. The synthesis is to only allow predictive dialing against **deterministic near-future completion events**, not loose statistical guesses.
2. **Deterministic Agent Completion Milestones:**
   Instead of guessing talk times from a global average, monitor the agent's current call milestones (e.g., agent reaches "Wrap-up" or speech analytics detects the standard call closing script: *"Thank you for your payment, have a good day"*). When an agent enters the closing phase, the system flags that agent as **"Imminently Available" ($T_{\text{remaining}} \le 12\text{s}$)**.
3. **Pacing Against Imminent Capacity with Strict Leeway Caps:**
   The pacing engine is only permitted to dial against agents in the closing window ($H \approx \text{carrier setup time}$). The maximum number of predictive outbound calls is strictly clamped to:
   $$\text{MaxPredictiveCalls} = A_{\text{avail}} + A_{\text{closing}}$$
4. **Instant Carrier Cancellation & Audio Parking Safeguards:**
   If the external call connects before an agent has finished, the Telecom Provider interface immediately triggers a priority bridge into an interactive IVR greeting with custom audio (*"Please hold one moment while we connect you to your representative"*), giving a 2–3 second buffer. If no agent becomes available within 2.0 seconds, the system aborts the outbound attempt before customer answer or logs an immediate compliance warning.
5. **Continuous Progressive Fallback:**
   If answer rates spike above a 2-sigma deviation, or ringing calls exceed available + closing agents, the Safety Controller automatically collapses the dialer back to pure 1:1 Progressive mode.

---

## Interview Defense & Common Q&A

### Q1: Two workers try to reserve the same agent at exactly the same time. Walk us through what happens.
> **Answer:** Both workers query the database and may both see the agent in `AVAILABLE` state with `version = 1`. Both attempt to reserve the agent by executing an atomic Compare-And-Swap query:
> ```sql
> UPDATE agents SET state = 'RESERVED', version = 2 
> WHERE id = :id AND state = 'AVAILABLE' AND version = 1;
> ```
> The underlying relational database serializes row-level locks on the agent row. Worker 1 executes first: the row matches `state = 'AVAILABLE'` and `version = 1`, updating 1 row. Worker 1 receives `rowsUpdated = 1` and proceeds with the reservation. When Worker 2 executes, the agent row now has `state = 'RESERVED'` and `version = 2`. The `WHERE` clause does not match; the query returns `rowsUpdated = 0`. Worker 2 detects the race loss, discards the agent, and moves to the next candidate. No in-memory locks or race conditions can bypass this.

### Q2: Your database says the agent is AVAILABLE, but your cache says RESERVED. Which one wins?
> **Answer:** The **Database always wins.** In our architecture, the database is the authoritative single source of truth for all critical allocation state. Any cache (whether in-memory or Redis) is strictly treated as an uncommitted, read-through hint. If there is a divergence, the cache is treated as stale, invalidated, and refreshed from the database.

### Q3: The provider sends ANSWERED, your worker crashes, and then COMPLETED arrives. What happens?
> **Answer:** 
> 1. When `ANSWERED` arrived, `CallEventLog` recorded the event, and `Call` was set to `ANSWERED`.
> 2. The worker crashed.
> 3. When `COMPLETED` arrives (either to a surviving worker or upon restart), `ProviderEventProcessor` checks the `call_event_logs` table. Because `COMPLETED` has a unique `providerEventId`, it is accepted.
> 4. `CallStateMachine` inspects the current call state (`ANSWERED`). Transitioning from `ANSWERED` to `COMPLETED` is a valid transition in `VALID_TRANSITIONS`.
> 5. The call state is set to `COMPLETED`, ended timestamp and duration are recorded, and the agent is transitioned to `WRAP_UP` and then freed to `AVAILABLE`. The system recovers cleanly with zero manual intervention.

### Q4: Your model predicted a 70% answer rate. It suddenly drops to 10%. How does the system protect itself?
> **Answer:** 
> 1. **Immediate Ringing Exposure Cap:** When the answer rate drops, calls ring longer without answering. The Safety Controller detects that `ringingCalls` exceeds `availableAgents * maxRingingExposureRatio` and immediately rejects new pacing requests.
> 2. **Bayesian Smoother Adjustment:** As failed and unanswered attempts accumulate, the Bayesian smoother formula updates $P_{\text{answer}}$ downward.
> 3. **Automatic Throttling:** The Pacing Engine sees lower net capacity and throttles proposed dials. If answer rates become volatile, the Safety Controller automatically triggers a `FALLBACK_PROGRESSIVE` outcome, capping dials strictly to 1:1 available agents.

---

## License

This project is licensed under the Apache License 2.0. Built for the Tech Assignment – Hiring 2026.
