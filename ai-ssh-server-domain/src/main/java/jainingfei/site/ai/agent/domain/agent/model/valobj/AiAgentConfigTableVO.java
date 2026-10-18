package jainingfei.site.ai.agent.domain.agent.model.valobj;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Ai Agent 智能体配置表值对象
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/11/29 10:54
 */
@Data
public class AiAgentConfigTableVO {

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 智能体配置
     */
    private Agent agent;

    /**
     * 智能体模块
     */
    private Module module;

    @Data
    public static class Agent {

        /**
         * 智能体ID
         */
        private String agentId;

        /**
         * 智能体名称
         */
        private String agentName;

        /**
         * 智能体描述
         */
        private String agentDesc;

    }

    @Data
    public static class Module {

        private ChatModel chatModel;

        private List<Agent> agents;

        private List<AgentWorkflow> agentWorkflows;

        private Runner runner;

        @Data
        public static class ChatModel {

            /**
             * 模型通道 id：deepseek-native（DeepSeek 原生 SDK）/ openai-compatible（OpenAI 协议）。
             * <p>空 = 默认通道（openai-compatible）；未知取值在装配期直接报错（列出可选值），
             * 不静默退回默认通道。
             */
            private String provider;

            private String baseUrl;
            private String apiKey;
            private String model;

            /**
             * 模型上下文窗口大小（tokens），来自配置文件 chat-model.context-size。
             * <p>仅作前端展示与信息回传使用，与会话治理的压缩触发阈值（compact.token-threshold）
             * 相互独立——治理阈值代表"接近模型窗口上限但留缓存余量"的经验值。
             */
            private Long contextSize;

            /**
             * 推理强度：deepseek-native 通道为 high / max；openai-compatible 通道原样透传给服务端
             * （minimal、low、medium、high…，仅推理模型生效；对齐教程 2-9）
             */
            private String reasoningEffort;

            /** 采样温度（模型默认 = null） */
            private Double temperature;

            /** 单次回复最大 token 数（模型默认 = null） */
            private Integer maxTokens;

            /** 核采样 top-p（模型默认 = null） */
            private Double topP;

            private List<ToolMcp> toolMcpList;
            private List<ToolSkills> toolSkillsList;

            @Data
            public static class ToolMcp {

                private RemoteServerParameters remote;

                private StdioServerParameters stdio;

                private LocalParameters local;

                @Data
                public static class RemoteServerParameters {
                    private String name;
                    private String url;
                    private Integer requestTimeout = 3000;
                    private Map<String, String> headers;

                }

                @Data
                public static class StdioServerParameters {
                    private String name;
                    private Integer requestTimeout = 3000;
                    private ServerParameters serverParameters;

                    @Data
                    public static class ServerParameters {
                        private String command;
                        private List<String> args;
                        private Map<String, String> env;

                    }
                }

                @Data
                public static class LocalParameters {
                    private String name;
                }

            }

            @Data
            public static class ToolSkills {

                /**
                 * 类型；directory（用户配置的，映射进来的）、resource（放到工程下的）
                 */
                private String type = "directory";

                /**
                 * 路径；
                 */
                private String path;

            }
        }

        @Data
        public static class Agent {
            private String name;
            private String instruction;
            private String description;
            private String outputKey;

            /**
             * 本智能体挂载的子智能体名单（YAML 键 sub-agents，教程 2-10）。
             * <p>父子关系是配置而不是代码：声明了子智能体的 Agent 在装配期会被重建为
             * "只持有派发工具"的编排者（不再直挂 executeCommand），未声明者行为与旧版一致。
             */
            private List<String> subAgents;

            /**
             * 是否只读智能体（YAML 键 read-only）。
             * <p>只读智能体不挂载 writeRemoteFile 工具；诊断类子智能体应设为 true，
             * 让"只收集证据、不改系统"从提示词约束升级为工具边界。
             */
            private Boolean readOnly;

        }

        @Data
        public static class AgentWorkflow {
            /**
             * 类型；loop、parallel、sequential
             */
            private String type;
            private String name;
            private List<String> subAgents;
            private String description;
            private Integer maxIterations = 3;

        }

        @Data
        public static class Runner {
            private String agentName;
            private List<String> pluginNameList;
        }
    }
}
