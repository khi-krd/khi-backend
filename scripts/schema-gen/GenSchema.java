import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.tool.schema.internal.SchemaCreatorImpl;
import org.hibernate.tool.schema.spi.GenerationTarget;

import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class GenSchema {
    static final List<String> CMDS = new ArrayList<>();

    static class Collect implements GenerationTarget {
        public void prepare() {}
        public void accept(String c) { CMDS.add(c); }
        public void release() {}
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Path out  = Paths.get(args[1]);

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", PostgreSQLDialect.class.getName())
                .applySetting("hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")
                .applySetting("hibernate.implicit_naming_strategy",
                        "org.springframework.boot.hibernate.SpringImplicitNamingStrategy")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .applySetting("hibernate.jdbc.time_zone", "UTC")
                .build();

        MetadataSources sources = new MetadataSources(registry);
        List<String> names;
        try (Stream<Path> s = Files.walk(root)) {
            names = s.filter(p -> p.toString().endsWith(".class"))
                     .map(p -> root.relativize(p).toString()
                             .replace(java.io.File.separatorChar, '.')
                             .replaceAll("\\.class$", ""))
                     .sorted().collect(Collectors.toList());
        }
        int e = 0, m = 0, em = 0, cv = 0;
        for (String n : names) {
            Class<?> c;
            try { c = Class.forName(n, false, GenSchema.class.getClassLoader()); }
            catch (Throwable t) { continue; }
            boolean add = false;
            if (c.isAnnotationPresent(Entity.class))           { e++;  add = true; }
            if (c.isAnnotationPresent(MappedSuperclass.class)) { m++;  add = true; }
            if (c.isAnnotationPresent(Embeddable.class))       { em++; add = true; }
            if (c.isAnnotationPresent(Converter.class))        { cv++; add = true; }
            if (add) sources.addAnnotatedClass(c);
        }
        System.err.printf("entities=%d mappedSuperclass=%d embeddable=%d converters=%d%n", e, m, em, cv);

        Metadata metadata = sources.buildMetadata();
        new SchemaCreatorImpl(registry).doCreation(metadata, true, new Collect());

        Files.write(out, CMDS.stream().map(c -> c + ";").collect(Collectors.toList()));
        System.err.printf("commands=%d -> %s%n", CMDS.size(), out);
    }
}
