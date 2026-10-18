package jainingfei.site.ai.agent.trigger.http;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jainingfei.site.ai.agent.api.dto.SftpFileDTO;
import jainingfei.site.ai.agent.api.dto.SftpListResponseDTO;
import jainingfei.site.ai.agent.api.dto.SftpOperationRequestDTO;
import jainingfei.site.ai.agent.api.dto.SftpPreviewResponseDTO;
import jainingfei.site.ai.agent.api.response.Response;
import jainingfei.site.ai.agent.domain.filesystem.model.valobj.SftpDirectoryVO;
import jainingfei.site.ai.agent.domain.filesystem.model.valobj.SftpFileVO;
import jainingfei.site.ai.agent.domain.filesystem.model.valobj.SftpPreviewVO;
import jainingfei.site.ai.agent.domain.filesystem.service.IFileSystemService;
import jainingfei.site.ai.agent.types.enums.ResponseCode;
import jainingfei.site.ai.agent.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 远程文件系统 HTTP 控制器——浏览 / 预览 / 上传 / 下载 / 新建 / 重命名与移动 / 删除 / 覆盖写入。
 *
 * <p>路径安全与限额在 {@code IFileSystemService}，本类只做协议适配。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sftp/{connectionId}")
public class FileSystemController {

    @Resource
    private IFileSystemService fileSystemService;

    @GetMapping("/list")
    public Response<SftpListResponseDTO> list(@PathVariable String connectionId,
                                              @RequestParam(required = false, defaultValue = ".") String path) {
        try {
            SftpDirectoryVO directory = fileSystemService.list(connectionId, path);
            SftpListResponseDTO dto = new SftpListResponseDTO();
            dto.setPath(directory.getPath());
            dto.setParent(directory.getParent());
            dto.setTotal(directory.getTotal());
            dto.setTruncated(directory.isTruncated());
            dto.setFiles(directory.getFiles() == null ? List.of()
                    : directory.getFiles().stream().map(FileSystemController::toDTO).collect(Collectors.toList()));
            return ok(dto);
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("列目录失败 connectionId={} path={}", connectionId, path, e);
            return fail(ResponseCode.UN_ERROR, e.getMessage());
        }
    }

    @GetMapping("/stat")
    public Response<SftpFileDTO> stat(@PathVariable String connectionId,
                                      @RequestParam String path) {
        try {
            return ok(toDTO(fileSystemService.stat(connectionId, path)));
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            return fail(ResponseCode.UN_ERROR, e.getMessage());
        }
    }

    @GetMapping("/preview")
    public Response<SftpPreviewResponseDTO> preview(@PathVariable String connectionId,
                                                    @RequestParam String path) {
        try {
            SftpPreviewVO preview = fileSystemService.preview(connectionId, path);
            SftpPreviewResponseDTO dto = new SftpPreviewResponseDTO();
            dto.setPath(preview.getPath());
            dto.setContent(preview.getContent());
            dto.setSize(preview.getSize());
            dto.setTruncated(preview.isTruncated());
            dto.setBinary(preview.isBinary());
            dto.setEncoding(preview.getEncoding());
            return ok(dto);
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("预览失败 connectionId={} path={}", connectionId, path, e);
            return fail(ResponseCode.UN_ERROR, e.getMessage());
        }
    }

    @PostMapping("/mkdir")
    public Response<Void> mkdir(@PathVariable String connectionId,
                                @RequestBody SftpOperationRequestDTO request) {
        try {
            fileSystemService.mkdir(connectionId, request.getPath());
            return ok(null);
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("创建目录失败 connectionId={} path={}", connectionId, request.getPath(), e);
            return fail(ResponseCode.UN_ERROR, e.getMessage());
        }
    }

    @PostMapping("/rename")
    public Response<Void> rename(@PathVariable String connectionId,
                                 @RequestBody SftpOperationRequestDTO request) {
        try {
            fileSystemService.rename(connectionId, request.getPath(), request.getNewPath());
            return ok(null);
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("重命名失败 connectionId={} path={}", connectionId, request.getPath(), e);
            return fail(ResponseCode.UN_ERROR, e.getMessage());
        }
    }

    @PostMapping("/delete")
    public Response<Void> delete(@PathVariable String connectionId,
                                 @RequestBody SftpOperationRequestDTO request) {
        try {
            fileSystemService.delete(connectionId, request.getPath(), request.isRecursive());
            return ok(null);
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("删除失败 connectionId={} path={}", connectionId, request.getPath(), e);
            return fail(ResponseCode.UN_ERROR, e.getMessage());
        }
    }

    @PostMapping("/write")
    public Response<Void> write(@PathVariable String connectionId,
                                @RequestBody SftpOperationRequestDTO request) {
        try {
            fileSystemService.writeText(connectionId, request.getPath(), request.getContent());
            return ok(null);
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("写入失败 connectionId={} path={}", connectionId, request.getPath(), e);
            return fail(ResponseCode.UN_ERROR, e.getMessage());
        }
    }

    @PostMapping("/upload")
    public Response<SftpFileDTO> upload(@PathVariable String connectionId,
                                        @RequestParam(required = false, defaultValue = ".") String path,
                                        @RequestParam("file") MultipartFile file) {
        try {
            String fileName = file.getOriginalFilename();
            fileSystemService.upload(connectionId, path, fileName, file.getInputStream(), file.getSize());
            String uploaded = joinPath(path, fileName);
            return ok(toDTO(fileSystemService.stat(connectionId, uploaded)));
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("上传失败 connectionId={} path={}", connectionId, path, e);
            return fail(ResponseCode.UN_ERROR, e.getMessage());
        }
    }

    @GetMapping("/download")
    public void download(@PathVariable String connectionId,
                         @RequestParam String path,
                         HttpServletResponse response) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        try {
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader("Content-Disposition", ContentDisposition.attachment()
                    .filename(fileName, StandardCharsets.UTF_8).build().toString());
            try (OutputStream out = response.getOutputStream()) {
                fileSystemService.download(connectionId, path, out);
            }
        } catch (Exception e) {
            log.error("下载失败 connectionId={} path={}", connectionId, path, e);
            if (!response.isCommitted()) {
                response.reset();
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                try {
                    response.getWriter().write("{\"code\":\"0001\",\"info\":\"下载失败:"
                            + escapeJson(e.getMessage()) + "\",\"data\":null}");
                } catch (Exception ignored) {
                    // 响应已不可写
                }
            }
        }
    }

    // ==================== 内部 ====================

    private static String joinPath(String directory, String fileName) {
        if (directory == null || directory.isBlank() || ".".equals(directory)) {
            return fileName;
        }
        return directory.endsWith("/") ? directory + fileName : directory + "/" + fileName;
    }

    private static SftpFileDTO toDTO(SftpFileVO vo) {
        SftpFileDTO dto = new SftpFileDTO();
        dto.setName(vo.getName());
        dto.setPath(vo.getPath());
        dto.setType(vo.getType() == null ? "other" : vo.getType().getCode());
        dto.setSize(vo.getSize());
        dto.setModifiedAt(vo.getModifiedAt());
        dto.setPermissions(vo.getPermissions());
        dto.setPermissionsText(vo.getPermissionsText());
        dto.setHidden(vo.isHidden());
        dto.setLinkTarget(vo.getLinkTarget());
        dto.setOwner(vo.getOwner());
        return dto;
    }

    private static <T> Response<T> ok(T data) {
        return Response.<T>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    private static <T> Response<T> fail(AppException e) {
        return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
    }

    private static <T> Response<T> fail(ResponseCode code, String info) {
        return Response.<T>builder()
                .code(code.getCode())
                .info(info == null ? code.getInfo() : info)
                .build();
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
