package io.kyligence.ragagent.core.prompt;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptServiceImpl implements PromptService {

    private final PromptMapper promptMapper;
    private final PromptRenderer renderer;

    @Override
    @Transactional
    public Prompt save(Long workspaceId, SavePromptCommand cmd) {
        Prompt existing = findByNameVersion(workspaceId, cmd.name(), cmd.version());
        if (existing != null) {
            existing.setTemplate(cmd.template());
            existing.setVariablesSchema(cmd.variablesSchema());
            existing.setDescription(cmd.description());
            existing.setUpdatedAt(LocalDateTime.now());
            promptMapper.updateById(existing);
            return existing;
        }
        Prompt p = new Prompt();
        p.setWorkspaceId(workspaceId);
        p.setName(cmd.name());
        p.setVersion(cmd.version());
        p.setTemplate(cmd.template());
        p.setVariablesSchema(cmd.variablesSchema());
        p.setDescription(cmd.description());
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        promptMapper.insert(p);
        return p;
    }

    @Override
    @Transactional(readOnly = true)
    public String render(Long workspaceId, String name, String version,
                         Map<String, Object> variables) {
        Prompt p = resolvePrompt(workspaceId, name, version);
        return renderer.render(p.getTemplate(), variables == null ? Map.of() : variables);
    }

    @Override
    @Transactional(readOnly = true)
    public Prompt get(Long workspaceId, String name, String version) {
        return findByNameVersion(workspaceId, name, version);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Prompt> list(Long workspaceId) {
        return promptMapper.selectList(
                Wrappers.<Prompt>lambdaQuery()
                        .eq(Prompt::getWorkspaceId, workspaceId)
                        .orderByAsc(Prompt::getName)
                        .orderByDesc(Prompt::getVersion));
    }

    @Override
    @Transactional
    public void delete(Long workspaceId, String name, String version) {
        Prompt p = findByNameVersion(workspaceId, name, version);
        if (p == null) {
            throw new PlatformException(ErrorCode.PROMPT_NOT_FOUND,
                    "Prompt not found: " + name + "@" + version);
        }
        promptMapper.deleteById(p.getId());
    }

    private Prompt findByNameVersion(Long workspaceId, String name, String version) {
        if (PromptUri.LATEST.equals(version)) {
            return promptMapper.selectOne(
                    Wrappers.<Prompt>lambdaQuery()
                            .eq(Prompt::getWorkspaceId, workspaceId)
                            .eq(Prompt::getName, name)
                            .orderByDesc(Prompt::getUpdatedAt)
                            .last("LIMIT 1"));
        }
        return promptMapper.selectOne(
                Wrappers.<Prompt>lambdaQuery()
                        .eq(Prompt::getWorkspaceId, workspaceId)
                        .eq(Prompt::getName, name)
                        .eq(Prompt::getVersion, version));
    }

    private Prompt resolvePrompt(Long workspaceId, String name, String version) {
        Prompt p = findByNameVersion(workspaceId, name, version);
        if (p == null) {
            throw new PlatformException(ErrorCode.PROMPT_NOT_FOUND,
                    "Prompt not found: " + name + "@" + version);
        }
        return p;
    }
}
