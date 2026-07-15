package jainingfei.site.ai.agent.domain.agent.service.armory.matter.skills.impl;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import jainingfei.site.ai.agent.domain.agent.model.valobj.AiAgentConfigTableVO;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.mcp.client.ToolCallbackBaseTool;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import jainingfei.site.ai.agent.domain.agent.service.armory.matter.toolset.StaticToolset;
import lombok.extern.slf4j.Slf4j;

import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Skills → ADK {@link BaseToolset}。
 * <p>
 * V2.1: 产出从 {@code ToolCallback[]} 改为 {@link BaseToolset}。
 */
@Slf4j
@Service
public class DefaultToolSkillsCreateService implements ToolSkillsCreateService {

    @Override
    public BaseToolset createToolset(List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList) throws Exception {
        List<ToolCallback> all = new ArrayList<>();
        for (AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills : toolSkillsList) {
            all.addAll(buildToolCallbacks(toolSkills));
        }
        List<BaseTool> baseTools = new ArrayList<>();
        for (ToolCallback cb : all) baseTools.add(new ToolCallbackBaseTool(cb));
        log.info("Skills → BaseToolset, total:{}", all.size());
        return new StaticToolset(baseTools);
    }

    private List<ToolCallback> buildToolCallbacks(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) {
        String type = toolSkills.getType();
        String path = toolSkills.getPath();
        List<ToolCallback> callbacks = new ArrayList<>();
        if ("directory".equals(type)) callbacks.add(SkillsTool.builder().addSkillsDirectory(path).build());
        if ("resource".equals(type)) callbacks.add(SkillsTool.builder().addSkillsResource(new ClassPathResource(path)).build());
        return callbacks;
    }

    @Override
    public String getSkillsInstruction(List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList) throws Exception {
        if (toolSkillsList == null || toolSkillsList.isEmpty()) {
            return "";
        }

        StringBuilder instruction = new StringBuilder();
        instruction.append("\n\n## Skills 能力说明\n\n");
        instruction.append("你拥有以下 Skills，可通过调用 `Skill` 工具来执行：\n\n");

        for (AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills : toolSkillsList) {
            String path = toolSkills.getPath();
            String type = toolSkills.getType();

            // 扫描 path 下所有 SKILL.md 文件
            String locationPattern;
            if ("resource".equals(type)) {
                locationPattern = "classpath:" + path + "/**/SKILL.md";
            } else if ("directory".equals(type)) {
                locationPattern = "file:" + path + "/**/SKILL.md";
            } else {
                continue;
            }

            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources;
            try {
                resources = resolver.getResources(locationPattern);
            } catch (Exception e) {
                log.warn("扫描 SKILL.md 失败 pattern:{} error:{}", locationPattern, e.getMessage());
                continue;
            }

            for (Resource resource : resources) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    String content = reader.lines().collect(Collectors.joining("\n"));
                    String filename = resource.getFilename();
                    log.info("加载 SKILL.md: {}", filename);

                    // 去掉 YAML front matter (--- ... ---)
                    if (content.startsWith("---")) {
                        int end = content.indexOf("---", 3);
                        if (end > 0) {
                            content = content.substring(end + 3).trim();
                        }
                    }

                    instruction.append("### ").append(filename).append("\n");
                    instruction.append(content).append("\n\n");
                }
            }
        }

        return instruction.toString();
    }
}
