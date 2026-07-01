package jainingfei.site.ai.agent.api;

import jainingfei.site.ai.agent.api.dto.SshConnectionCreateRequestDTO;
import jainingfei.site.ai.agent.api.dto.SshConnectionResponseDTO;
import jainingfei.site.ai.agent.api.response.Response;

import java.util.List;

/**
 * SSH 连接 API 服务接口
 */
public interface ISshConnectionApiService {

    /**
     * 创建 SSH 连接配置
     */
    Response<SshConnectionResponseDTO> createConnection(SshConnectionCreateRequestDTO request);

    /**
     * 建立 SSH 连接
     */
    Response<Void> connect(String connectionId);

    /**
     * 断开 SSH 连接
     */
    Response<Void> disconnect(String connectionId);

    /**
     * 查询用户的所有连接列表
     */
    Response<List<SshConnectionResponseDTO>> queryUserConnections(String userId);

    /**
     * 查询连接详情
     */
    Response<SshConnectionResponseDTO> queryConnectionDetail(String connectionId);

    /**
     * 删除连接
     */
    Response<Void> deleteConnection(String connectionId);
}
