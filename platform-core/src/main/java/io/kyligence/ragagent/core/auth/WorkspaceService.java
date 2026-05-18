package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Workspace 业务逻辑服务
 */
@Service
public class WorkspaceService {

    public record WorkspaceView(String id, String name, String ownerId, Role role) {}

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;

    public WorkspaceService(WorkspaceMapper workspaceMapper, WorkspaceMemberMapper memberMapper) {
        this.workspaceMapper = workspaceMapper;
        this.memberMapper = memberMapper;
    }

    public List<WorkspaceView> listForUser(String userId) {
        List<WorkspaceMember> members = memberMapper.selectList(
            Wrappers.<WorkspaceMember>lambdaQuery().eq(WorkspaceMember::getUserId, userId));
        return members.stream().map(m -> {
            Workspace w = workspaceMapper.selectById(m.getWorkspaceId());
            return new WorkspaceView(w.getId(), w.getName(), w.getOwnerId(), m.getRole());
        }).toList();
    }

    @Transactional
    public WorkspaceView create(String userId, String name) {
        Workspace ws = new Workspace();
        ws.setName(name);
        ws.setOwnerId(userId);
        workspaceMapper.insert(ws);

        WorkspaceMember m = new WorkspaceMember();
        m.setWorkspaceId(ws.getId());
        m.setUserId(userId);
        m.setRole(Role.OWNER);
        memberMapper.insert(m);

        return new WorkspaceView(ws.getId(), ws.getName(), ws.getOwnerId(), Role.OWNER);
    }
}
