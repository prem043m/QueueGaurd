package com.queueguard.qms.web;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.queueguard.common.security.JwtUtil;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class Phase3ControllerTest {
    private static final long MANAGER_ID = 101L;
    private static final long STUDENT_ID = 102L;
    private static final long INTERVIEWER_ID = 103L;
    private static final long OTHER_STUDENT_ID = 104L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("qms_phase3_web_test")
                    .withUsername("qms_test")
                    .withPassword("qms_test");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtUtil jwtUtil;

    private long organizationId;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE TABLE organizations RESTART IDENTITY CASCADE");
        Instant now = Instant.now();
        organizationId = jdbc.queryForObject("""
                insert into organizations
                    (name, organization_type, status, created_at, updated_at)
                values ('Web Test Org', 'COLLEGE', 'ACTIVE', ?, ?)
                returning id
                """, Long.class, Timestamp.from(now), Timestamp.from(now));
        addMember(MANAGER_ID, "QUEUE_MANAGER");
        addMember(STUDENT_ID, "MEMBER");
        addMember(INTERVIEWER_ID, "INTERVIEWER");
        addMember(OTHER_STUDENT_ID, "MEMBER");
    }

    @Test
    void placementDriveControllerCreatesAndSerializesDrive() throws Exception {
        mockMvc.perform(post(orgPath("/drives"))
                        .header("Authorization", bearer(MANAGER_ID))
                        .contentType("application/json")
                        .content("""
                                {
                                  "name":"Campus Hiring",
                                  "description":"Engineering roles",
                                  "registrationCapacity":25,
                                  "registrationOpensAt":"2030-01-01T10:00:00Z",
                                  "registrationClosesAt":"2030-01-02T10:00:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.organizationId").value(organizationId))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.registrationCapacity").value(25));
    }

    @Test
    void candidateProfileControllerCreatesOwnProfile() throws Exception {
        mockMvc.perform(post(orgPath("/candidates/profiles"))
                        .header("Authorization", bearer(STUDENT_ID))
                        .contentType("application/json")
                        .content("""
                                {
                                  "studentName":"Student One",
                                  "email":"student@example.test",
                                  "graduationYear":2030,
                                  "cgpa":8.75,
                                  "skills":"Java, SQL"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.userId").value(STUDENT_ID))
                .andExpect(jsonPath("$.data.organizationId").value(organizationId))
                .andExpect(jsonPath("$.data.cgpa").value(8.75));
    }

    @Test
    void candidateApplicationControllerSubmitsForTheOwnProfile() throws Exception {
        long profileId = insertProfile(STUDENT_ID);
        long driveId = insertDrive("REGISTRATION_OPEN");

        mockMvc.perform(post(orgPath("/drives/" + driveId + "/applications"))
                        .header("Authorization", bearer(STUDENT_ID))
                        .contentType("application/json")
                        .content("{\"candidateProfileId\":" + profileId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.applicationStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.eligibilityStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.studentUserId").value(STUDENT_ID));
    }

    @Test
    void interviewRoundControllerCreatesRoundWithValidSchedule() throws Exception {
        long driveId = insertDrive("PUBLISHED");
        long queueId = insertQueueConfiguration();

        mockMvc.perform(post(orgPath("/drives/" + driveId + "/rounds"))
                        .header("Authorization", bearer(MANAGER_ID))
                        .contentType("application/json")
                        .content("""
                                {
                                  "queueConfigurationId":%d,
                                  "roundNumber":1,
                                  "name":"Technical",
                                  "roundType":"TECHNICAL",
                                  "scheduledStartAt":"2030-01-03T10:00:00Z",
                                  "scheduledEndAt":"2030-01-03T11:00:00Z",
                                  "qualificationCriteria":"Complete the coding interview"
                                }
                                """.formatted(queueId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.placementDriveId").value(driveId))
                .andExpect(jsonPath("$.data.queueConfigurationId").value(queueId))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void interviewerAssignmentControllerAssignsMemberWithInterviewerRole() throws Exception {
        long driveId = insertDrive("PUBLISHED");
        long queueId = insertQueueConfiguration();
        long roundId = insertRound(driveId, queueId);

        mockMvc.perform(post(orgPath("/rounds/" + roundId + "/assignments"))
                        .header("Authorization", bearer(MANAGER_ID))
                        .contentType("application/json")
                        .content("""
                                {
                                  "interviewerUserId":%d,
                                  "panelName":"Panel A",
                                  "servicePoint":"Room 2"
                                }
                                """.formatted(INTERVIEWER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.interviewerUserId").value(INTERVIEWER_ID))
                .andExpect(jsonPath("$.data.status").value("ASSIGNED"));
    }

    @Test
    void fullReplacementResourcesUsePutWhilePatchIsReservedForPartialOperations()
            throws Exception {
        long draftDriveId = insertDrive("DRAFT");
        long profileId = insertProfile(STUDENT_ID);
        long publishedDriveId = insertDrive("PUBLISHED");
        long queueId = insertQueueConfiguration();
        long roundId = insertRound(publishedDriveId, queueId);

        mockMvc.perform(put(orgPath("/drives/" + draftDriveId))
                        .header("Authorization", bearer(MANAGER_ID))
                        .contentType("application/json")
                        .content("""
                                {"name":"Revised Drive","description":"Updated",
                                 "registrationOpensAt":"2030-01-01T10:00:00Z",
                                 "registrationClosesAt":"2030-01-02T10:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Revised Drive"));

        mockMvc.perform(put(orgPath("/candidates/profiles/" + profileId))
                        .header("Authorization", bearer(STUDENT_ID))
                        .contentType("application/json")
                        .content("""
                                {"studentName":"Updated Student",
                                 "email":"updated@example.test","skills":"Java"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentName").value("Updated Student"));

        mockMvc.perform(put(orgPath("/rounds/" + roundId))
                        .header("Authorization", bearer(MANAGER_ID))
                        .contentType("application/json")
                        .content("""
                                {"name":"Updated Technical","roundType":"TECHNICAL",
                                 "scheduledStartAt":"2030-01-03T10:00:00Z",
                                 "scheduledEndAt":"2030-01-03T11:00:00Z",
                                 "qualificationCriteria":"Updated criteria"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Updated Technical"));

        mockMvc.perform(patch(orgPath("/drives/" + draftDriveId))
                        .header("Authorization", bearer(MANAGER_ID)))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch(orgPath("/candidates/profiles/" + profileId))
                        .header("Authorization", bearer(STUDENT_ID)))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch(orgPath("/rounds/" + roundId))
                        .header("Authorization", bearer(MANAGER_ID)))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void invalidBodiesUseTheStandardErrorEnvelopeForAllControllers() throws Exception {
        long driveId = insertDrive("PUBLISHED");
        long queueId = insertQueueConfiguration();

        expectValidationFailure(post(orgPath("/drives"))
                .content("""
                        {"name":"Bad window","registrationCapacity":2,
                         "registrationOpensAt":"2030-01-02T10:00:00Z",
                         "registrationClosesAt":"2030-01-01T10:00:00Z"}
                        """));
        expectValidationFailure(post(orgPath("/candidates/profiles"))
                .content("""
                        {"studentName":"Student","email":"not-an-email"}
                        """));
        expectValidationFailure(post(orgPath("/drives/" + driveId + "/applications"))
                .content("{\"candidateProfileId\":0}"));
        expectValidationFailure(post(orgPath("/drives/" + driveId + "/rounds"))
                .content("""
                        {"queueConfigurationId":%d,"roundNumber":1,"name":"Bad",
                         "roundType":"TECHNICAL",
                         "scheduledStartAt":"2030-01-03T11:00:00Z",
                         "scheduledEndAt":"2030-01-03T10:00:00Z",
                         "qualificationCriteria":"criteria"}
                        """.formatted(queueId)));
        expectValidationFailure(post(orgPath("/rounds/" + insertRound(driveId, queueId)
                        + "/assignments"))
                .content("{\"interviewerUserId\":0}"));
    }

    @Test
    void missingAuthenticationIsRejectedByJwtFilterOnEveryController() throws Exception {
        for (String path : protectedPaths()) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").isString())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Test
    void authorizationAndOrganizationIsolationUseRealTenantServices() throws Exception {
        long driveId = insertDrive("DRAFT");
        long otherProfileId = insertProfile(OTHER_STUDENT_ID);
        insertProfile(INTERVIEWER_ID);
        long otherOrganizationId = jdbc.queryForObject("""
                insert into organizations
                    (name, organization_type, status, created_at, updated_at)
                values ('Other Org', 'COLLEGE', 'ACTIVE', now(), now())
                returning id
                """, Long.class);

        mockMvc.perform(post(orgPath("/drives"))
                        .header("Authorization", bearer(STUDENT_ID))
                        .contentType("application/json")
                        .content("""
                                {"name":"Unauthorized","registrationCapacity":5}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get(orgPath("/candidates/profiles/" + otherProfileId))
                        .header("Authorization", bearer(STUDENT_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get("/api/v1/organizations/" + otherOrganizationId
                        + "/drives/" + driveId)
                        .header("Authorization", bearer(MANAGER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get(orgPath("/drives"))
                        .header("Authorization", bearer(INTERVIEWER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get(orgPath("/drives/" + driveId + "/rounds"))
                        .header("Authorization", bearer(INTERVIEWER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get(orgPath("/candidates/profiles/me"))
                        .header("Authorization", bearer(INTERVIEWER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get(orgPath("/applications/me"))
                        .header("Authorization", bearer(INTERVIEWER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void interviewRoundReadsRequireManagerOrAssignedInterviewerRole() throws Exception {
        long driveId = insertDrive("PUBLISHED");
        long queueId = insertQueueConfiguration();
        long roundId = insertRound(driveId, queueId);
        insertInterviewerAssignment(roundId, INTERVIEWER_ID);
        insertInterviewerAssignment(roundId, OTHER_STUDENT_ID);

        mockMvc.perform(get(orgPath("/rounds/" + roundId))
                        .header("Authorization", bearer(INTERVIEWER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(roundId));

        mockMvc.perform(get(orgPath("/rounds/" + roundId))
                        .header("Authorization", bearer(OTHER_STUDENT_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void meEndpointsBindIdentityToJwtActorNotCallerSuppliedIdentifiers() throws Exception {
        long profileId = insertProfile(STUDENT_ID);
        insertProfile(OTHER_STUDENT_ID);
        long driveId = insertDrive("REGISTRATION_OPEN");
        long otherProfileId = jdbc.queryForObject("""
                select id from candidate_profiles
                where organization_id = ? and user_id = ?
                """, Long.class, organizationId, OTHER_STUDENT_ID);
        insertApplication(driveId, profileId, STUDENT_ID);
        insertApplication(driveId, otherProfileId, OTHER_STUDENT_ID);

        mockMvc.perform(get(orgPath("/candidates/profiles/me"))
                        .header("Authorization", bearer(STUDENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(STUDENT_ID));

        mockMvc.perform(get(orgPath("/applications/me"))
                        .param("userId", Long.toString(OTHER_STUDENT_ID))
                        .header("Authorization", bearer(STUDENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].studentUserId").value(STUDENT_ID));

        mockMvc.perform(get(orgPath("/assignments/me"))
                        .param("interviewerUserId", Long.toString(OTHER_STUDENT_ID))
                        .header("Authorization", bearer(INTERVIEWER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    private void expectValidationFailure(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request.header("Authorization", bearer(MANAGER_ID))
                        .contentType("application/json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private long insertDrive(String status) {
        Instant now = Instant.now();
        return jdbc.queryForObject("""
                insert into placement_drives
                    (organization_id, name, registration_capacity, status,
                     registration_opens_at, registration_closes_at, created_by,
                     created_at, updated_at, version)
                values (?, 'Test Drive', 10, ?, ?, ?, ?, ?, ?, 0)
                returning id
                """, Long.class, organizationId, status,
                Timestamp.from(now.minusSeconds(3600)), Timestamp.from(now.plusSeconds(3600)),
                MANAGER_ID, Timestamp.from(now), Timestamp.from(now));
    }

    private long insertProfile(long userId) {
        Instant now = Instant.now();
        return jdbc.queryForObject("""
                insert into candidate_profiles
                    (organization_id, user_id, student_name, email,
                     created_at, updated_at, version)
                values (?, ?, ?, ?, ?, ?, 0)
                returning id
                """, Long.class, organizationId, userId, "Student " + userId,
                "student" + userId + "@example.test", Timestamp.from(now), Timestamp.from(now));
    }

    private long insertQueueConfiguration() {
        Instant now = Instant.now();
        return jdbc.queryForObject("""
                insert into queue_configurations
                    (organization_id, name, queue_code, queue_type, ordering_policy,
                     status, priority_enabled, created_by, created_at, updated_at, version)
                values (?, 'Interview queue', 'INTERVIEW', 'GENERIC', 'FIFO',
                        'DRAFT', false, ?, ?, ?, 0)
                returning id
                """, Long.class, organizationId, MANAGER_ID,
                Timestamp.from(now), Timestamp.from(now));
    }

    private long insertRound(long driveId, long queueId) {
        Instant now = Instant.now();
        return jdbc.queryForObject("""
                insert into interview_rounds
                    (organization_id, placement_drive_id, queue_configuration_id,
                     round_number, name, round_type, scheduled_start_at, scheduled_end_at,
                     status, qualification_criteria, created_by, created_at, updated_at, version)
                values (?, ?, ?, 1, 'Technical', 'TECHNICAL', ?, ?, 'DRAFT',
                        'Interview criteria', ?, ?, ?, 0)
                returning id
                """, Long.class, organizationId, driveId, queueId,
                Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now.plusSeconds(7200)),
                MANAGER_ID, Timestamp.from(now), Timestamp.from(now));
    }

    private void insertInterviewerAssignment(long roundId, long interviewerId) {
        Instant now = Instant.now();
        jdbc.update("""
                insert into interviewer_assignments
                    (organization_id, interview_round_id, interviewer_user_id, panel_name,
                     assigned_at, status, created_at, updated_at, version)
                values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
                """, organizationId, roundId, interviewerId, "Panel " + interviewerId,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    private void insertApplication(long driveId, long profileId, long studentId) {
        Instant now = Instant.now();
        jdbc.update("""
                insert into candidate_applications
                    (organization_id, placement_drive_id, application_number,
                     eligibility_status, application_status, applied_at,
                     candidate_profile_id, student_user_id, created_at, updated_at, version)
                values (?, ?, ?, 'PENDING', 'SUBMITTED', ?, ?, ?, ?, ?, 0)
                """, organizationId, driveId, studentId, Timestamp.from(now), profileId,
                studentId, Timestamp.from(now), Timestamp.from(now));
    }

    private void addMember(long userId, String role) {
        long membershipId = jdbc.queryForObject("""
                insert into memberships
                    (organization_id, auth_user_id, status, created_at, updated_at)
                values (?, ?, 'ACTIVE', now(), now())
                returning id
                """, Long.class, organizationId, userId);
        jdbc.update("insert into membership_roles (membership_id, role) values (?, ?)",
                membershipId, role);
    }

    private String bearer(long userId) {
        return "Bearer " + jwtUtil.issue("user-" + userId, Map.of("uid", userId));
    }

    private String orgPath(String path) {
        return "/api/v1/organizations/" + organizationId + path;
    }

    private static java.util.List<String> protectedPaths() {
        return java.util.List.of(
                "/api/v1/organizations/1/drives",
                "/api/v1/organizations/1/candidates/profiles/me",
                "/api/v1/organizations/1/applications/me",
                "/api/v1/organizations/1/rounds?status=DRAFT",
                "/api/v1/organizations/1/assignments/me");
    }
}
