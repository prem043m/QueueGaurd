package com.queueguard.qms.web;
import com.queueguard.common.dto.ApiResponse; import com.queueguard.qms.dto.QmsDtos.*; import com.queueguard.qms.security.JwtIdentityFilter; import com.queueguard.qms.service.TenantService; import jakarta.validation.Valid; import org.springframework.web.bind.annotation.*; import java.util.List;
@RestController @RequestMapping("/v1") public class TenantController {
 private final TenantService service; public TenantController(TenantService s){service=s;}
 @PostMapping("/organizations") public ApiResponse<OrganizationView> create(@RequestAttribute(JwtIdentityFilter.USER_ID) Long u,@Valid @RequestBody CreateOrganizationRequest r){return ApiResponse.ok("Organization created",service.onboard(u,r));}
 @GetMapping("/organizations/{id}") public ApiResponse<OrganizationView> get(@RequestAttribute(JwtIdentityFilter.USER_ID) Long u,@PathVariable Long id){return ApiResponse.ok(service.organization(u,id));}
 @GetMapping("/organizations/mine") public ApiResponse<List<MembershipView>> mine(@RequestAttribute(JwtIdentityFilter.USER_ID) Long u){return ApiResponse.ok(service.mine(u));}
 @GetMapping("/organizations/{id}/members") public ApiResponse<List<MembershipView>> list(@RequestAttribute(JwtIdentityFilter.USER_ID) Long u,@PathVariable Long id){return ApiResponse.ok(service.list(u,id));}
 @GetMapping("/organizations/{org}/members/{id}") public ApiResponse<MembershipView> member(@RequestAttribute(JwtIdentityFilter.USER_ID) Long u,@PathVariable Long org,@PathVariable Long id){return ApiResponse.ok(service.membership(u,org,id));}
 @PostMapping("/organizations/{id}/members") public ApiResponse<MembershipView> add(@RequestAttribute(JwtIdentityFilter.USER_ID) Long u,@PathVariable Long id,@Valid @RequestBody AddMembershipRequest r){return ApiResponse.ok("Membership created",service.add(u,id,r));}
 @PatchMapping("/organizations/{org}/members/{id}/roles") public ApiResponse<MembershipView> roles(@RequestAttribute(JwtIdentityFilter.USER_ID) Long u,@PathVariable Long org,@PathVariable Long id,@Valid @RequestBody UpdateRolesRequest r){return ApiResponse.ok("Roles updated",service.roles(u,org,id,r));}
 @PatchMapping("/organizations/{org}/members/{id}/status") public ApiResponse<MembershipView> status(@RequestAttribute(JwtIdentityFilter.USER_ID) Long u,@PathVariable Long org,@PathVariable Long id,@Valid @RequestBody UpdateMembershipStatusRequest r){return ApiResponse.ok("Membership status updated",service.status(u,org,id,r));}
}
