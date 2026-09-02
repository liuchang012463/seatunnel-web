package org.apache.seatunnel.plugin.datasource.localfile.catalog;

import org.apache.seatunnel.plugin.datasource.api.datasource.FileDataSourceCatalog;
import org.apache.seatunnel.plugin.datasource.localfile.param.LocalFileConnectionParam;
import org.apache.seatunnel.plugin.datasource.localfile.util.LocalPathUtils;
import org.apache.seatunnel.web.spi.bean.vo.FileEntryVO;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Lists and uploads files inside the configured local base directory. */
public class LocalFileCatalog implements FileDataSourceCatalog {

    private static final String TYPE_DIRECTORY = "DIRECTORY";
    private static final String TYPE_FILE = "FILE";

    private final LocalFileConnectionParam param;

    public LocalFileCatalog(LocalFileConnectionParam param) {
        this.param = param;
    }

    @Override
    public List<FileEntryVO> listEntries(String path) {
        Path basePath = LocalPathUtils.resolveBase(param.getBasePath());
        Path dir = LocalPathUtils.resolveWithin(basePath, path);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Path is not a directory: " + path);
        }
        List<FileEntryVO> entries = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            children.sorted(Comparator.comparing((Path child) -> !Files.isDirectory(child))
                            .thenComparing(child -> child.getFileName().toString(),
                                    String.CASE_INSENSITIVE_ORDER))
                    .forEach(child -> entries.add(toEntry(basePath, child)));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list local directory: " + dir, e);
        }
        return entries;
    }

    @Override
    public List<OptionVO> listOptions() {
        return listEntries(param.getBasePath()).stream().map(entry -> {
            OptionVO option = new OptionVO();
            option.setLabel(entry.getName());
            option.setValue(entry.getPath());
            option.setDescription(entry.getType());
            return option;
        }).collect(Collectors.toList());
    }

    @Override
    public String uploadEntry(String path, String fileName, InputStream inputStream, long size) {
        Path basePath = LocalPathUtils.resolveBase(param.getBasePath());
        LocalPathUtils.ensureDirectoryWithin(basePath, path);
        Path dir = LocalPathUtils.resolveWithin(basePath, path);
        String safeName = sanitizeFileName(fileName);
        Path target = LocalPathUtils.resolveFileWithin(basePath, path, safeName);
        try (InputStream in = inputStream) {
            Files.createDirectories(dir);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store uploaded file: " + safeName, e);
        }
        return target.toString();
    }

    private FileEntryVO toEntry(Path basePath, Path child) {
        FileEntryVO entry = new FileEntryVO();
        entry.setName(child.getFileName().toString());
        entry.setPath(child.toString());
        boolean directory = Files.isDirectory(child);
        entry.setType(directory ? TYPE_DIRECTORY : TYPE_FILE);
        if (!directory) {
            entry.setSize(LocalPathUtils.sizeOf(child));
            entry.setModifiedTime(LocalPathUtils.modifiedTimeOf(child));
        }
        return entry;
    }

    private static String sanitizeFileName(String fileName) {
        String name = org.apache.commons.lang3.StringUtils.trimToEmpty(fileName);
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("Invalid file name: " + fileName);
        }
        return name;
    }
}
