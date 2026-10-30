package notification;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** JSON bodies for the REST/JSON baseline (same fields as the protobuf messages). */
public final class Dto {
    private Dto() {}

    public record Event(@JsonProperty("event_id") String eventId,
                        @JsonProperty("recipient_id") String recipientId,
                        @JsonProperty("channel") String channel,
                        @JsonProperty("template_id") String templateId,
                        @JsonProperty("data") Map<String, String> data) {}

    public record Ack(@JsonProperty("event_id") String eventId,
                      @JsonProperty("status") String status) {}
}
