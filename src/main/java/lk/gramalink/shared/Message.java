package lk.gramalink.shared;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/** Protocol envelope. Payload is always an object; optional fields may be omitted. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(MessageType type, String correlationId, String token, ObjectNode payload) {
    public Message {
        Objects.requireNonNull(type, "Message type is required.");
        Objects.requireNonNull(payload, "Message payload must be a JSON object.");
    }

    public Message(MessageType type, ObjectNode payload) {
        this(type, null, null, payload);
    }
}
