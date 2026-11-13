package lk.gramalink.server.backend;

import java.util.List;
import java.util.UUID;

public final class Models {
    private Models() { }
    public enum Role { CITIZEN, GN, DS, ADMIN }
    public enum Status { SUBMITTED, RECEIVED_BY_GN, UNDER_GN_REVIEW, VERIFIED_BY_GN, NEEDS_CORRECTION, FORWARDED_TO_DS, RETURNED_TO_GN, APPROVED, REJECTED }
    public enum Action { RECEIVE, REVIEW, VERIFY, REQUEST_CORRECTION, RESUBMIT, FORWARD, RETURN, APPROVE, REJECT }
    public record Actor(UUID userId) { }
    public record User(UUID id, String username, Role role, UUID officeId, String division, boolean active) { }
    public record Request(UUID id, UUID citizenId, UUID gnId, UUID dsId, UUID gnOfficeId, UUID dsOfficeId,
                          String division, String type, String title, String description, Status status, long version) { }
    public record CreateRequest(String type, String title, String description, String division) { }
    public record ChangeRequest(UUID requestId, long expectedVersion, Action action, String reason, String title, String description) { }
    public record Event(String type, List<String> recipients, Object payload) { }
    public record Result<T>(T value, List<Event> events) { }
    public record Chat(UUID requestId, String body) { }
    public record NewUser(String username, String password, Role role, UUID officeId, String division) { }
    public record NewOffice(String name, String kind, String division, UUID parentId) { }
}
