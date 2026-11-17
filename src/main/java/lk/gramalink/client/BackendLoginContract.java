package lk.gramalink.client;

import lk.gramalink.shared.Message;
import lk.gramalink.shared.MessageType;

import java.util.Optional;
import java.util.UUID;

final class BackendLoginContract {
    record Identity(String userId, String role) { }

    private BackendLoginContract() { }

    static Optional<Identity> identity(Message reply, String expectedRole) {
        if (reply == null || reply.type() != MessageType.LOGIN_OK
                || reply.token() != null
                || reply.correlationId() == null || reply.correlationId().isBlank()
                || !reply.payload().path("operation").isTextual()
                || !"LOGIN".equals(reply.payload().path("operation").textValue())) {
            return Optional.empty();
        }

        var data = reply.payload().path("data");
        if (!data.isObject() || !data.path("userId").isTextual()
                || !data.path("role").isTextual()) {
            return Optional.empty();
        }

        String userId = data.path("userId").textValue();
        String role = data.path("role").textValue();
        if (userId == null || role == null || !expectedRole.equals(role)) {
            return Optional.empty();
        }

        try {
            UUID parsed = UUID.fromString(userId);
            if (!parsed.toString().equals(userId)) return Optional.empty();
        } catch (IllegalArgumentException invalidId) {
            return Optional.empty();
        }
        return Optional.of(new Identity(userId, role));
    }

    static String displayName(Identity identity) {
        // LOGIN_OK has no display-name field; show the authoritative ID until ME is integrated.
        return identity.userId();
    }
}
