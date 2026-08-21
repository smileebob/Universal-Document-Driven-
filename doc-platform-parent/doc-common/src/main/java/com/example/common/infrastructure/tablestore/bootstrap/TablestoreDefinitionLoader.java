package com.example.common.infrastructure.tablestore.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.example.common.infrastructure.tablestore.metadata.TableDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 表/索引定义文件加载器（TS-06：定义随代码仓库版本化管理）。
 *
 * <p>按文件名 V 后序号（数值序，防 V10 字典序排在 V2 前）升序加载
 * {@code tablestore/V{序号}__{描述}.json}；跨文件重名表 fail-fast；
 * 版本号只增不改、已发布定义文件禁止修改（等价 Flyway 不可变迁移脚本，design/03 §八）。</p>
 */
public final class TablestoreDefinitionLoader {

    /** 默认定义文件位置（doc-common 内置 V1 基线） */
    public static final String DEFAULT_LOCATION_PATTERN = "classpath*:tablestore/V*.json";

    /** 文件名 V 序号提取（评审修复 Minor-14：按数值排序，防 V10<V2 字典序） */
    private static final Pattern VERSION_PATTERN = Pattern.compile("^V(\\d+)");

    private final ResourcePatternResolver resolver;
    private final ObjectMapper objectMapper;

    public TablestoreDefinitionLoader() {
        this(new PathMatchingResourcePatternResolver());
    }

    public TablestoreDefinitionLoader(ResourcePatternResolver resolver) {
        this.resolver = resolver;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 按默认位置加载全部版本定义（按 V 序号数值升序）。
     *
     * @return 有序表定义清单
     */
    public List<TableDefinition> loadAll() {
        return load(DEFAULT_LOCATION_PATTERN);
    }

    /**
     * 按指定位置模式加载定义文件。
     *
     * @param locationPattern Spring 资源模式（如 classpath*:tablestore/V*.json）
     * @return 有序表定义清单
     */
    public List<TableDefinition> load(String locationPattern) {
        Resource[] resources;
        try {
            resources = resolver.getResources(locationPattern);
        } catch (IOException e) {
            throw new IllegalStateException("读取 Tablestore 定义文件失败: " + locationPattern, e);
        }
        List<Resource> sorted = new ArrayList<>(List.of(resources));
        // 评审修复 Minor-14：按 V 后数字数值排序（防 V10 字典序排在 V2 前），同序回退文件名
        sorted.sort(Comparator
                .comparingInt((Resource r) -> versionOf(r.getFilename()))
                .thenComparing(Resource::getFilename,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        List<TableDefinition> definitions = new ArrayList<>();
        Map<String, String> tableSources = new HashMap<>();
        for (Resource resource : sorted) {
            DefinitionFile file = parse(resource);
            for (TableDefinition table : file.getTables()) {
                // 评审修复 Minor-14：跨文件重名表 fail-fast（后版本不得重定义同名表）
                String previous = tableSources.putIfAbsent(table.getName(), resource.getFilename());
                if (previous != null) {
                    throw new IllegalStateException("Tablestore 定义文件存在重名表: "
                            + table.getName() + "（" + previous + " 与 "
                            + resource.getFilename() + "）");
                }
            }
            definitions.addAll(file.getTables());
        }
        return definitions;
    }

    /** 文件名 V 序号数值提取（无序号排最后） */
    private static int versionOf(String filename) {
        if (filename == null) {
            return Integer.MAX_VALUE;
        }
        Matcher matcher = VERSION_PATTERN.matcher(filename);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    private DefinitionFile parse(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            DefinitionFile file = objectMapper.readValue(in, DefinitionFile.class);
            if (file.getTables().isEmpty()) {
                throw new IllegalStateException("Tablestore 定义文件无表定义: " + resource.getFilename());
            }
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("解析 Tablestore 定义文件失败: " + resource.getFilename(), e);
        }
    }
}
