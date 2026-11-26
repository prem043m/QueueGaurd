package com.queueguard.qms.web;

import com.queueguard.common.dto.ApiResponse;
import com.queueguard.qms.dto.AnalyticsDtos.ApplicationFunnel;
import com.queueguard.qms.dto.AnalyticsDtos.DepartmentAnalytics;
import com.queueguard.qms.dto.AnalyticsDtos.DriveAnalyticsSummary;
import com.queueguard.qms.dto.AnalyticsDtos.OrganizationOverview;
import com.queueguard.qms.dto.AnalyticsDtos.InterviewerWorkload;
import com.queueguard.qms.dto.AnalyticsDtos.RoundProgression;
import com.queueguard.qms.security.JwtIdentityFilter;
import com.queueguard.qms.service.AnalyticsService;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/analytics")
public class AnalyticsController {
    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/overview")
    @Transactional(readOnly = true)
    public ApiResponse<OrganizationOverview> overview(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return ApiResponse.ok(analyticsService.overview(actorId, orgId, from, to));
    }

    @GetMapping("/drives/{driveId}")
    @Transactional(readOnly = true)
    public ApiResponse<DriveAnalyticsSummary> driveSummary(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long driveId) {
        return ApiResponse.ok(analyticsService.driveSummary(actorId, orgId, driveId));
    }

    @GetMapping("/drives/{driveId}/applications/funnel")
    @Transactional(readOnly = true)
    public ApiResponse<ApplicationFunnel> applicationFunnel(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long driveId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return ApiResponse.ok(
                analyticsService.applicationFunnel(actorId, orgId, driveId, from, to));
    }

    @GetMapping("/departments")
    @Transactional(readOnly = true)
    public ApiResponse<List<DepartmentAnalytics>> departmentAnalytics(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return ApiResponse.ok(
                analyticsService.departmentAnalytics(actorId, orgId, from, to));
    }

    @GetMapping("/interviewers/workload")
    @Transactional(readOnly = true)
    public ApiResponse<InterviewerWorkload> interviewerWorkload(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @RequestParam(required = false) Long driveId,
            @RequestParam(required = false) Long roundId,
            @RequestParam(required = false) Long interviewerId) {
        return ApiResponse.ok(analyticsService.interviewerWorkload(
                actorId, orgId, driveId, roundId, interviewerId));
    }

    @GetMapping("/drives/{driveId}/rounds/progression")
    @Transactional(readOnly = true)
    public ApiResponse<RoundProgression> roundProgression(
            @RequestAttribute(JwtIdentityFilter.USER_ID) Long actorId,
            @PathVariable Long orgId,
            @PathVariable Long driveId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return ApiResponse.ok(analyticsService.roundProgression(
                actorId, orgId, driveId, from, to));
    }
}
