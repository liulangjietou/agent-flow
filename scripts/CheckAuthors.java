import com.sun.source.tree.ClassTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/**
 * 使用 Java 语法树检查全部命名类型的作者标签，不依赖外部库。
 * @author owlzhangfq@gmail.com
 */
class CheckAuthors {
    private static final String AUTHOR_TAG = "@author owlzhangfq@gmail.com";
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(".git", "target", "node_modules", "dist", "data");

    /** 检查指定仓库目录，作者缺失或源码无法解析时以非零状态退出。 */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A Java 17 or newer JDK is required");
        }
        List<Path> paths = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return EXCLUDED_DIRECTORIES.contains(directory.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (file.toString().endsWith(".java")) paths.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        if (paths.isEmpty()) throw new IllegalStateException("No Java sources found under " + root);
        try (var manager = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("-proc:none", "--release", "17"), null, manager.getJavaFileObjectsFromPaths(paths));
            var trees = DocTrees.instance(task);
            int[] count = {0};
            var missing = new ArrayList<String>();
            for (var unit : task.parse()) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitClass(ClassTree type, Void unused) {
                        // 匿名类没有独立声明名称，不属于类级作者标签检查对象。
                        if (!type.getSimpleName().toString().isBlank()) {
                            count[0]++;
                            var comment = trees.getDocCommentTree(getCurrentPath());
                            boolean found = comment != null && comment.getBlockTags().stream()
                                    .anyMatch(tag -> AUTHOR_TAG.equals(tag.toString()));
                            if (!found) missing.add(unit.getSourceFile().getName() + ":" + type.getSimpleName());
                        }
                        return super.visitClass(type, unused);
                    }
                }.scan(unit, null);
            }
            var errors = diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR).toList();
            System.out.printf("Java files=%d, named types=%d, missing author tags=%d, parse errors=%d%n",
                    paths.size(), count[0], missing.size(), errors.size());
            missing.forEach(System.err::println);
            errors.forEach(System.err::println);
            if (!missing.isEmpty() || !errors.isEmpty()) System.exit(1);
        }
    }
}
