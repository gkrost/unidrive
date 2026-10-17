import java.io.*;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class CdsBench {
    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    static final Map<String, List<Double>> measurements = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("baselineJava cdsJava jar scratch samples");
        Path[] javaBins = {Path.of(args[0]), Path.of(args[1])};
        Path jar = Path.of(args[2]);
        Path scratch = Path.of(args[3]);
        int samples = Integer.parseInt(args[4]);
        if (samples < 1) throw new IllegalArgumentException("samples must be positive");
        for (Path path : javaBins) if (!Files.isRegularFile(path)) throw new IllegalArgumentException("missing Java: " + path);
        if (!Files.isRegularFile(jar)) throw new IllegalArgumentException("missing jar: " + jar);
        Files.createDirectories(scratch);
        System.err.printf("OS=%s, arch=%s, helper_jdk=%s, processors=%d%n",
            System.getProperty("os.name"), System.getProperty("os.arch"),
            System.getProperty("java.runtime.version"), Runtime.getRuntime().availableProcessors());
        System.out.println("variant,iteration,metric,milliseconds,exit_code,detail");
        for (int i = -1; i < samples; i++) {
            int[] order = i % 2 == 0 ? new int[]{0, 1} : new int[]{1, 0};
            for (int variant : order) {
                String label = variant == 0 ? "baseline" : "cds";
                Path run = scratch.resolve(label + "-" + i);
                if (Files.exists(run)) throw new IllegalArgumentException("sample directory already exists: " + run);
                Path config = run.resolve("config");
                Path remote = run.resolve("remote");
                Path sync = run.resolve("sync");
                Files.createDirectories(config);
                Files.createDirectories(remote);
                Files.createDirectories(sync);
                Files.createDirectories(run.resolve("tmp"));
                Files.createDirectories(run.resolve("home"));
                Files.createDirectories(run.resolve("data"));
                String toml = "[general]\ndefault_profile = \"bench\"\n" +
                    "[providers.bench]\ntype = \"localfs\"\nmode = \"mount\"\n" +
                    "root_path = \"" + path(remote) + "\"\n" +
                    "sync_root = \"" + path(sync) + "\"\ndaemon_poll_seconds = 0\n";
                Files.writeString(config.resolve("config.toml"), toml);
                String metric = i < 0 ? "warmup" : "status";
                Path statusLog = run.resolve("status.log");
                ProcessBuilder status = command(javaBins[variant], jar, run, config, "status");
                status.redirectErrorStream(true).redirectOutput(statusLog.toFile());
                long start = System.nanoTime();
                Process statusProc = status.start();
                if (!statusProc.waitFor(30, TimeUnit.SECONDS)) {
                    statusProc.destroyForcibly();
                    statusProc.waitFor(5, TimeUnit.SECONDS);
                    throw new RuntimeException("status timeout: " + statusLog);
                }
                double ms = elapsed(start);
                if (statusProc.exitValue() != 0) throw new RuntimeException("status failed: " + statusLog + "\n" + Files.readString(statusLog));
                if (!Files.readString(statusLog).contains("Local Filesystem"))
                    throw new RuntimeException("status did not render the localfs profile: " + statusLog);
                print(label, i, metric, ms, statusProc.exitValue(), "ok");

                Path daemonLog = run.resolve("daemon.log");
                ProcessBuilder daemon = command(javaBins[variant], jar, run, config, "daemon", "run", "--poll-interval", "0");
                daemon.redirectErrorStream(true).redirectOutput(daemonLog.toFile());
                Path socket;
                if (WINDOWS) {
                    socket = run.resolve("tmp/unidrive-ipc/unidrive-bench.sock");
                } else {
                    String uid = unixUid();
                    Path runDir = Path.of("/run/user/" + uid);
                    socket = Files.isDirectory(runDir)
                        ? runDir.resolve("unidrive-bench.sock")
                        : run.resolve("tmp/unidrive-ipc-" + uid + "/unidrive-bench.sock");
                }
                if (Files.exists(socket)) throw new RuntimeException("socket already exists: " + socket);
                if (WINDOWS && socket.toString().getBytes(StandardCharsets.UTF_8).length >= 100)
                    throw new IllegalArgumentException("Windows socket path is too long; use a shorter scratch path: " + socket);
                start = System.nanoTime();
                Process daemonProc = daemon.start();
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    boolean ready = false;
                    while (System.nanoTime() < deadline && daemonProc.isAlive()) {
                        if (Files.exists(socket) && probe(socket)) { ready = true; break; }
                        Thread.sleep(5);
                    }
                    ms = elapsed(start);
                    if (!ready) throw new RuntimeException("daemon not ready: " + daemonLog + "\n" + Files.readString(daemonLog));
                    print(label, i, i < 0 ? "warmup" : "daemon_ready", ms, 0, "ok");
                } finally {
                    daemonProc.destroy();
                    if (!daemonProc.waitFor(10, TimeUnit.SECONDS)) {
                        daemonProc.destroyForcibly();
                        daemonProc.waitFor(5, TimeUnit.SECONDS);
                    }
                    if (daemonProc.isAlive()) throw new RuntimeException("daemon did not stop: " + daemonProc.pid());
                }
            }
        }
        System.err.println("variant,metric,samples,median_ms,min_ms,max_ms");
        for (var entry : measurements.entrySet()) {
            List<Double> values = new ArrayList<>(entry.getValue());
            Collections.sort(values);
            int n = values.size();
            double median = n % 2 == 0 ? (values.get(n / 2 - 1) + values.get(n / 2)) / 2 : values.get(n / 2);
            System.err.printf(Locale.ROOT, "%s,%d,%.3f,%.3f,%.3f%n",
                entry.getKey(), n, median, values.getFirst(), values.getLast());
        }
    }

    static ProcessBuilder command(Path javaBin, Path jar, Path run, Path config, String... command) {
        List<String> argv = new ArrayList<>();
        argv.add(javaBin.toString());
        argv.add("-Djava.io.tmpdir=" + run.resolve("tmp"));
        argv.add("-Duser.home=" + run.resolve("home"));
        argv.add("-jar");
        argv.add(jar.toString());
        argv.add("--config-dir");
        argv.add(config.toString());
        argv.add("-p");
        argv.add("bench");
        argv.addAll(Arrays.asList(command));
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.environment().put("UNIDRIVE_CONFIG_DIR", config.toString());
        builder.environment().put("XDG_CACHE_HOME", run.resolve("data").toString());
        builder.environment().put("XDG_DATA_HOME", run.resolve("data").toString());
        builder.environment().put("LOCALAPPDATA", run.resolve("data").toString());
        builder.environment().put("APPDATA", run.resolve("data").toString());
        builder.environment().put("HOME", run.resolve("home").toString());
        return builder;
    }

    static String path(Path p) { return p.toAbsolutePath().toString().replace('\\', '/'); }
    static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    static boolean probe(Path socket) {
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            channel.configureBlocking(false);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            ByteBuffer request = ByteBuffer.wrap("{\"verb\":\"daemon.status\"}\n".getBytes(StandardCharsets.UTF_8));
            while (request.hasRemaining() && System.nanoTime() < deadline) {
                if (channel.write(request) == 0) Thread.sleep(1);
            }
            if (request.hasRemaining()) return false;
            ByteBuffer reply = ByteBuffer.allocate(4096);
            while (System.nanoTime() < deadline) {
                int read = channel.read(reply);
                if (read < 0) return false;
                String response = new String(reply.array(), 0, reply.position(), StandardCharsets.UTF_8);
                if (response.contains("\"ok\":true") && response.contains("\"protocol_version\"")) return true;
                if (read == 0) Thread.sleep(1);
            }
        } catch (IOException | InterruptedException ignored) { }
        return false;
    }
    static String unixUid() throws Exception {
        Process p = new ProcessBuilder("id", "-u").start();
        String result = new String(p.getInputStream().readAllBytes()).trim();
        if (p.waitFor() != 0) throw new RuntimeException("id -u failed");
        return result;
    }
    static void print(String variant, int i, String metric, double ms, int code, String detail) {
        System.out.printf(Locale.ROOT, "%s,%d,%s,%.3f,%d,%s%n", variant, i, metric, ms, code, detail);
        System.out.flush();
        if (i >= 0) measurements.computeIfAbsent(variant + "," + metric, ignored -> new ArrayList<>()).add(ms);
    }
}
