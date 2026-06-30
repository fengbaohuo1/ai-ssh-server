package jainingfei.site.ai.agent.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ChatCommandEntity {
    private String agentId;
    private String userId;
    private String sessionId;
    private List<Content.Text> texts;
    private List<Content.File> files;
    private List<Content.InlineData> inlineDatas;

    @Data
    public static class Content {
        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class Text {
            private String message;
        }

        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class File {
            private String fileUri;
            private String mimeType;
        }

        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class InlineData {
            private byte[] bytes;
            private String mimeType;
        }
    }
}
