package ai.rever.boss.plugin.runtime;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** JDK-only child process used to exercise ProcessHandle without a platform shell. */
public final class DetachedSleeperFixture {
    private DetachedSleeperFixture() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "sleep".equals(args[0])) {
            TimeUnit.MINUTES.sleep(2);
            return;
        }
        if (args.length != 1 || !"launch".equals(args[0])) {
            throw new IllegalArgumentException("expected launch or sleep");
        }

        String executable =
                Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                System.getProperty("os.name").startsWith("Windows")
                                        ? "java.exe"
                                        : "java")
                        .toString();
        String classes =
                Path.of(
                                DetachedSleeperFixture.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI())
                        .toString();
        Process sleeper =
                new ProcessBuilder(executable, "-cp", classes, DetachedSleeperFixture.class.getName(), "sleep")
                        .start();
        System.out.println(sleeper.pid());
        System.out.flush();
    }
}
