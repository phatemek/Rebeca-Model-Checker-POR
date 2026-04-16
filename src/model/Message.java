package model;

import java.util.Arrays;

/**
 * An immutable message sitting in a rebec's queue.
 * Carries who sent it (needed for the `sender` keyword inside msgsrvs)
 * and any integer parameters passed with it.
 */
public final class Message {

    public final String   name;
    public final int      senderId;  // ID of the rebec that sent this message
    public final Object[] params;    // empty array when the message has no parameters

    public Message(String name, int senderId) {
        this.name     = name;
        this.senderId = senderId;
        this.params   = new Object[0];
    }

    public Message(String name, int senderId, Object... params) {
        this.name     = name;
        this.senderId = senderId;
        this.params   = params.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Message)) return false;
        Message m = (Message) o;
        return name.equals(m.name)
                && senderId == m.senderId
                && Arrays.equals(params, m.params);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * name.hashCode() + senderId) + Arrays.hashCode(params);
    }

    @Override
    public String toString() {
        return name + "(from=" + senderId + ")";
    }
}
