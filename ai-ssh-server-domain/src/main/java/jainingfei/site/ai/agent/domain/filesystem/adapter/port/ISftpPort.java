package jainingfei.site.ai.agent.domain.filesystem.adapter.port;

import jainingfei.site.ai.agent.domain.filesystem.model.valobj.SftpFileVO;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * SFTP 端口——领域层声明文件系统能力，基础设施层用 JSch ChannelSftp 实现。
 *
 * <p>本接口只做"通道操作"，路径安全策略与审计在
 * {@code IFileSystemService} 中完成；实现方可以假设传入路径已规范化。
 */
public interface ISftpPort {

    /** 列目录（目录不存在 / 无权限时抛异常） */
    List<SftpFileVO> list(String connectionId, String path);

    /** 查看单个文件/目录属性 */
    SftpFileVO stat(String connectionId, String path);

    /** 路径是否存在（不存在返回 false；权限等其他错误抛异常） */
    boolean exists(String connectionId, String path);

    /** 创建目录（父目录必须存在） */
    void mkdir(String connectionId, String path);

    /** 重命名 / 移动 */
    void rename(String connectionId, String oldPath, String newPath);

    /** 删除文件或目录；recursive=true 时递归删除目录内容 */
    void delete(String connectionId, String path, boolean recursive);

    /** 上传：把输入流写入远程路径（覆盖） */
    void upload(String connectionId, String path, InputStream inputStream, long size);

    /** 下载：把远程文件写入输出流 */
    void download(String connectionId, String path, OutputStream outputStream);

    /** 读取文件头部（最多 maxBytes）用于文本预览 */
    byte[] readPrefix(String connectionId, String path, int maxBytes);

    /** 写入文本/字节内容（覆盖） */
    void write(String connectionId, String path, byte[] content);
}
