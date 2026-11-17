package lk.gramalink.client;

import lk.gramalink.shared.Message;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Package-local view port. AdminGateway is the only production implementation. */
interface AdminWorkspace {
    default void refresh() { }
    default boolean usesBackendContract() { return false; }
    void requireAdministrator();
    boolean isAdministratorAuthorized();
    Optional<AdminData.Identity> identity();
    boolean supports(AdminData.Operation operation);
    default void validateCommand(AdminData.Command command) { }
    void setListener(AdminGateway.Listener listener);
    CompletableFuture<Message> perform(AdminData.Command command);
    CompletableFuture<Void> disconnect();
}
