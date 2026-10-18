package jainingfei.site.ai.agent.domain.filesystem.service;

import jainingfei.site.ai.agent.domain.filesystem.model.valobj.SftpDirectoryVO;
import jainingfei.site.ai.agent.domain.filesystem.model.valobj.SftpFileVO;
import jainingfei.site.ai.agent.domain.filesystem.model.valobj.SftpPreviewVO;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * 远程文件系统领域服务——路径安全、大小限制、审计都在这一层完成。
 *
 * <p>职责边界：
 * <pre>
 *   FileSystemController   协议适配（multipart / 下载响应头 / SSE 以外的 HTTP）
 *   IFileSystemService     用例编排 + 路径闸门 + 限额 + 审计
 *   ISftpPort              纯通道操作（JSch ChannelSftp）
 * </pre>
 */
public interface IFileSystemService {

    /** 列目录（目录优先、名称升序） */
    SftpDirectoryVO list(String connectionId, String path);

    /** 查看单条目属性 */
    SftpFileVO stat(String connectionId, String path);

    /** 创建目录 */
    void mkdir(String connectionId, String path);

    /** 重命名 / 移动 */
    void rename(String connectionId, String path, String newPath);

    /** 删除（目录需 recursive=true 才能清空） */
    void delete(String connectionId, String path, boolean recursive);

    /** 上传文件到指定目录 */
    void upload(String connectionId, String directory, String fileName,
                InputStream inputStream, long size);

    /** 下载文件到输出流 */
    void download(String connectionId, String path, OutputStream outputStream);

    /** 文本预览（上限由配置控制，二进制文件不返回内容） */
    SftpPreviewVO preview(String connectionId, String path);

    /**
     * 文本预览（可指定上限；实际读取 min(maxBytes, 配置上限)）。
     *
     * @param maxBytes 请求上限，<=0 表示使用配置默认值
     */
    SftpPreviewVO preview(String connectionId, String path, int maxBytes);

    /** 覆盖写入文本内容 */
    void writeText(String connectionId, String path, String content);
}
