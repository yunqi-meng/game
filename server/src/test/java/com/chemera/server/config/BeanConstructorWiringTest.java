package com.chemera.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 容器装配的静态闸门：凡是 Spring 要自己 new 的 bean，要么只有一个构造，要么明确指名一个。
 *
 * <p>为什么要有这个测试：本仓的测试全是 standalone MockMvc（手工 new 控制器 + Mockito 打依赖），
 * 没有一个 {@code @SpringBootTest}，所以<b>整条依赖图从来没被真正实例化过</b>。CurfewGuard 加第二个
 * 构造（给单测注入固定时钟与假快照）时，容器启动直接报
 * {@code NoSuchMethodException: CurfewGuard.<init>()}——单测全绿、一跑就崩，这类问题在别处没有网能接住。
 *
 * <p>它复述的是 Spring 自己的规则：候选构造多于一个又没有 {@code @Autowired} 指名的那个，
 * 容器会退回找无参构造；没有无参构造就启动失败。这种写法编译期不报错，只能扫。
 */
class BeanConstructorWiringTest {

    private static final List<Class<? extends java.lang.annotation.Annotation>> STEREOTYPES =
            List.of(Component.class, Service.class, Repository.class, Controller.class,
                    RestController.class, Configuration.class);

    @Test
    void everyBeanWithSeveralConstructorsNamesTheOneToUse() throws Exception {
        Path root = classesRoot();
        List<String> offenders = new ArrayList<>();
        int beans = 0;
        for (Path p : classFiles(root)) {
            String fqn = toClassName(root, p);
            if (fqn.contains("$")) continue;                 // 嵌套/匿名类不是组件扫描的目标
            Class<?> c = load(fqn);
            if (c == null || c.isInterface() || Modifier.isAbstract(c.getModifiers())) continue;
            if (!isBean(c)) continue;
            beans++;
            Constructor<?>[] ks = c.getDeclaredConstructors();
            if (ks.length <= 1) continue;                    // 唯一构造：容器直接用它，不必标注
            long chosen = Arrays.stream(ks)
                    .filter(k -> k.isAnnotationPresent(Autowired.class))
                    .filter(k -> k.getAnnotation(Autowired.class).required())
                    .count();
            if (chosen != 1) offenders.add(fqn + "（" + ks.length + " 个构造，@Autowired 指定了 " + chosen + " 个）");
        }
        // 扫不到东西本身就是失败：目录指错会让这个测试永远"通过"，那等于没有网
        assertTrue(beans >= 20, "只扫到 " + beans + " 个组件，多半是 classesRoot() 指错了目录：" + root);
        if (!offenders.isEmpty())
            fail("以下 bean 有多个构造却没指定首选构造，容器启动会退回找无参构造并失败：\n  "
                    + String.join("\n  ", offenders)
                    + "\n写法：给生产用的那个构造加 @Autowired，另一个留给单测注入假依赖。");
    }

    private boolean isBean(Class<?> c) {
        return STEREOTYPES.stream().anyMatch(c::isAnnotationPresent);
    }

    /** 主代码就在测试 classpath 上，直接用现成的加载器；initialize=false 免得静态块在扫描时跑起来。 */
    private Class<?> load(String fqn) {
        try {
            return Class.forName(fqn, false, getClass().getClassLoader());
        } catch (Throwable t) {
            return null;                                    // 加载不动的跳过：本测试只负责点名真组件
        }
    }

    /** mvn/IDE 的工作目录不一定是 server/，所以先按相对路径试，再拿测试类自己的位置反推。 */
    private Path classesRoot() {
        Path main = Path.of("target/classes").toAbsolutePath();
        if (Files.isDirectory(main)) return main;
        try {
            File f = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            Path cand = f.toPath().getParent().resolve("classes");     // target/test-classes → target/classes
            if (Files.isDirectory(cand)) return cand;
        } catch (Exception ignore) { /* 回相对路径，让下面的断言报出真实目录 */ }
        return main;
    }

    private List<Path> classFiles(Path root) throws Exception {
        if (!Files.isDirectory(root))
            fail("找不到 " + root + "：先编译再跑本测试（mvn -o test 会自动满足）");
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".class")).toList();
        }
    }

    private String toClassName(Path root, Path classFile) {
        return root.relativize(classFile).toString()
                .replace(File.separatorChar, '.')
                .replace('/', '.')
                .replaceAll("\\.class$", "");
    }
}
