package lk.gramalink.shared;

/** Message names shared by the client and server protocol. */
public enum MessageType {
    LOGIN,
    LOGIN_OK,
    CREATE_REQUEST,
    NEW_REQUEST,
    STATUS_UPDATE,
    REQUEST_UPDATED,
    CHAT_MESSAGE,
    NOTIFICATION,
    FILE_META,
    FILE_CHUNK,
    HEARTBEAT,
    ACK,
    ERROR,
    // Additive backend query/admin commands; existing wire names are unchanged.
    ME, GET_REQUEST, LIST_REQUESTS, GET_HISTORY, GET_CHAT, GET_NOTIFICATIONS,
    READ_NOTIFICATION, LIST_DOCUMENTS, LIST_OFFICES, LIST_USERS, CREATE_USER,
    SET_USER_ACTIVE, CREATE_OFFICE, RENAME_OFFICE, GET_STATISTICS, GET_AUDIT
}
