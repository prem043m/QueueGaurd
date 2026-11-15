package com.queueguard.qms.web;

import com.queueguard.common.dto.ApiResponse;
import com.queueguard.qms.dto.Phase3Dtos.ChangeInterviewerAssignmentStatusRequest;
import com.queueguard.qms.dto.Phase3Dtos.CreateInterviewerAssignmentRequest;
import com.queueguard.qms.dto.Phase3Dtos.InterviewerAssignmentView;
import com.queueguard.qms.security.JwtIdentityFilter;
import com.queueguard.qms.service.InterviewerAssignmentService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
public class InterviewerAssignmentController {
    private final InterviewerAssignmentService assignmentService;

    public InterviewerAssignmentController(InterviewerAssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @PostMapping("/rounds/{roundId}/assignments")
    public ApiResponse<InterviewerAssignmentView> assign(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long roundId,
            @Valid @RequestBody CreateInterviewerAssignmentRequest request) {
        return ApiResponse.ok("Interviewer assigned", InterviewerAssignmentView.from(
                assignmentService.assign(actorId, orgId, roundId,
                        request.interviewerUserId(), request.panelName(),
                        request.servicePoint())));
    }

    @GetMapping("/rounds/{roundId}/assignments")
    public ApiResponse<List<InterviewerAssignmentView>> listForRound(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long roundId) {
        return ApiResponse.ok(assignmentService.listForRound(actorId, orgId, roundId)
                .stream().map(InterviewerAssignmentView::from).toList());
    }

    @GetMapping("/assignments/me")
    public ApiResponse<List<InterviewerAssignmentView>> listMine(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId) {
        return ApiResponse.ok(assignmentService.listForInterviewer(actorId, orgId, actorId)
                .stream().map(InterviewerAssignmentView::from).toList());
    }

    @GetMapping("/assignments/{assignmentId}")
    public ApiResponse<InterviewerAssignmentView> get(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long assignmentId) {
        return ApiResponse.ok(InterviewerAssignmentView.from(
                assignmentService.get(actorId, orgId, assignmentId)));
    }

    @PatchMapping("/assignments/{assignmentId}")
    public ApiResponse<InterviewerAssignmentView> changeStatus(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long assignmentId,
            @Valid @RequestBody ChangeInterviewerAssignmentStatusRequest request) {
        return ApiResponse.ok("Interviewer assignment updated", InterviewerAssignmentView.from(
                assignmentService.changeStatus(actorId, orgId, assignmentId, request.status())));
    }
}
