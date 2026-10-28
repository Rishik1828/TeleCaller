package com.smartdialer.api;

import com.smartdialer.api.dto.ApiDtos.CreateAgentRequest;
import com.smartdialer.api.dto.ApiDtos.UpdateAgentStatusRequest;
import com.smartdialer.domain.enums.AgentState;
import com.smartdialer.domain.model.Agent;
import com.smartdialer.repository.AgentRepository;
import com.smartdialer.statemachine.AgentStateMachine;
import com.smartdialer.statemachine.TransitionResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentRepository agentRepository;
    private final AgentStateMachine agentStateMachine;

    public AgentController(AgentRepository agentRepository, AgentStateMachine agentStateMachine) {
        this.agentRepository = agentRepository;
        this.agentStateMachine = agentStateMachine;
    }

    @PostMapping
    public ResponseEntity<Agent> createAgent(@Valid @RequestBody CreateAgentRequest req) {
        Agent agent = new Agent(
            req.agentExternalId(),
            req.name(),
            req.state() != null ? req.state() : AgentState.AVAILABLE
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(agentRepository.save(agent));
    }

    @GetMapping
    public ResponseEntity<List<Agent>> listAgents(@RequestParam(required = false) AgentState state) {
        if (state != null) {
            return ResponseEntity.ok(agentRepository.findByState(state));
        }
        return ResponseEntity.ok(agentRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Agent> getAgent(@PathVariable Long id) {
        return agentRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<TransitionResult> updateStatus(@PathVariable Long id,
                                                         @Valid @RequestBody UpdateAgentStatusRequest req) {
        TransitionResult result = agentStateMachine.transition(id, req.state(), req.reason() != null ? req.reason() : "Manual API update");
        if (result.successful()) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
        }
    }
}
