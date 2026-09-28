package dev.minecraftmcp.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.minecraftmcp.MinecraftMcpClient;
import dev.minecraftmcp.mcp.McpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * The engine room: a queue of work and the doors that feed it.
 *
 * <p>Every request - whether it arrives over MCP or over the plain REST pair - becomes a list of steps. Steps
 * are run one tick at a time on the game thread, and the caller's future is completed when the last one is
 * done, carrying what each step did plus a fresh snapshot of the world. Nothing touches the game off the game
 * thread, and a slow request can never hold a frame.
 */
public final class AgentBridge {
    /** Set this system property to a port to override the config file; development runs use it. */
    public static final String PORT_PROPERTY = MinecraftMcpClient.PORT_PROPERTY;
    public static final int DEFAULT_PORT = 25585;

    private static final String CONFIG_FILE = "minecraft-mcp.json";
    private static final int MAX_CHAT_LINES = 40;
    private static final int STATE_RADIUS = 16;
    private static final int MAX_QUEUE = 8;
    private static final long WAIT_SECONDS = 150L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final ConcurrentLinkedDeque<Task> PENDING = new ConcurrentLinkedDeque<>();
    private static final List<String> CHAT = new ArrayList<>();

    private static Task running;
    private static AgentJob job;
    private static volatile boolean started;
    private static volatile boolean inWorld;

    /** The job the game is carrying out on its own, if there is one. */
    public static AgentJob job() {
        return job;
    }

    /**
     * Hands a job to the game. One at a time: a second job replaces the first, after stopping it cleanly so
     * the keys are never left held down.
     */
    public static JsonObject startJob(AgentJob next) {
        if (!started) {
            return error("the MCP server is not running");
        }
        if (job != null && !job.finished()) {
            job.stop(Minecraft.getInstance(), "replaced by a new job");
        }
        job = next;
        return next.status(Minecraft.getInstance());
    }

    public static JsonObject stopJob(String why) {
        if (job == null) {
            return error("no job is running");
        }
        job.stop(Minecraft.getInstance(), why);
        return job.status(Minecraft.getInstance());
    }

    private AgentBridge() {
    }

    /** Reads {@code config/minecraft-mcp.json}, writing it with defaults on first launch. 0 means "stay off". */
    public static int loadConfig() {
        String override = System.getProperty(PORT_PROPERTY, "").trim();
        Path file = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);

        JsonObject config = new JsonObject();
        try {
            if (Files.exists(file)) {
                config = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            } else {
                config.addProperty("enabled", true);
                config.addProperty("port", DEFAULT_PORT);
                config.addProperty("borderlessFullscreen", true);
                config.addProperty("_readme", "Local only: the server listens on 127.0.0.1 and can control the "
                        + "player. Set enabled to false to switch it off. borderlessFullscreen makes the game "
                        + "start in borderless fullscreen mode so you can watch the agent play.");
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(config) + System.lineSeparator(), StandardCharsets.UTF_8);
                MinecraftMcpClient.LOGGER.info("Wrote a fresh {} - the MCP server is on by default", file);
            }
        } catch (Exception problem) {
            MinecraftMcpClient.LOGGER.warn("Could not read {}, falling back to the defaults", file, problem);
        }

        if (!override.isEmpty()) {
            try {
                return Integer.parseInt(override);
            } catch (NumberFormatException bad) {
                MinecraftMcpClient.LOGGER.error("{} must be a port number, not \"{}\"", PORT_PROPERTY, override);
                return 0;
            }
        }

        if (config.has("enabled") && !config.get("enabled").getAsBoolean()) {
            return 0;
        }
        return config.has("port") ? config.get("port").getAsInt() : DEFAULT_PORT;
    }

    /** Whether the game should start in borderless fullscreen mode. */
    public static boolean isBorderlessFullscreen() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        try {
            if (Files.exists(file)) {
                JsonObject config = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                return !config.has("borderlessFullscreen") || config.get("borderlessFullscreen").getAsBoolean();
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    /** Starts listening, or logs why it could not. A port of 0 or less leaves the whole mod asleep. */
    public static void start(int port) {
        if (port <= 0) {
            MinecraftMcpClient.LOGGER.info("Minecraft MCP is off (config/{} has enabled=false)", CONFIG_FILE);
            return;
        }

        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            server.createContext("/health", exchange -> sendJson(exchange, 200, health()));
            server.createContext("/state", AgentBridge::act);
            server.createContext("/act", AgentBridge::act);
            server.createContext("/mcp", McpServer::handle);
            server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "minecraft-mcp");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            started = true;
            MinecraftMcpClient.LOGGER.info("Minecraft MCP listening on http://127.0.0.1:{}/mcp (this machine only)",
                    port);
        } catch (IOException taken) {
            MinecraftMcpClient.LOGGER.error(
                    "Could not listen on 127.0.0.1:{} - is another bridge already using that port?", port, taken);
        }
    }

    public static boolean isStarted() {
        return started;
    }

    /** A line of chat, a command's answer or a death message, kept so the agent can read what happened. */
    public static void remember(String line) {
        if (!started) {
            return;
        }
        synchronized (CHAT) {
            CHAT.add(line);
            while (CHAT.size() > MAX_CHAT_LINES) {
                CHAT.remove(0);
            }
        }
    }

    /**
     * Queues a piece of work and hands back the answer it will produce. The steps run one per tick; the future
     * completes with each step's report plus a fresh state, or with an explanation of what went wrong.
     */
    public static CompletableFuture<JsonObject> submit(List<AgentActions.Step> steps) {
        if (!started) {
            return CompletableFuture.completedFuture(error("the MCP server is not running"));
        }
        if (PENDING.size() >= MAX_QUEUE) {
            return CompletableFuture.completedFuture(
                    error("the game is already busy - " + PENDING.size() + " requests waiting"));
        }

        Task task = new Task(steps, AgentActions.ticks(steps));
        PENDING.add(task);
        return task.done;
    }

    /** Waits for a queued request to be carried out, and turns any failure into a JSON error object. */
    public static JsonObject await(CompletableFuture<JsonObject> future) {
        try {
            return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException slow) {
            return error("the game did not finish the action within " + WAIT_SECONDS + " s");
        } catch (Exception broken) {
            return error(broken.toString());
        }
    }

    /** One tick of the queue, called from the client's tick event. */
    public static void tick(Minecraft client) {
        if (!started) {
            return;
        }

        inWorld = client.level != null && client.player != null;

        if (running == null) {
            running = PENDING.poll();
            if (running != null) {
                running.deadline = System.currentTimeMillis() + Math.max(5_000L, running.ticks * 60L);
            }
        }

        if (running == null) {
            // Nothing was asked for, so the job gets the tick. This is what lets the game play on its own
            // while the agent is thinking, or has stopped asking.
            if (job != null) {
                if (job.finished()) {
                    AgentInput.release();
                } else if (inWorld) {
                    job.tick(client);
                } else {
                    job.stop(client, "the game is not in a world");
                }
            }
            return;
        }

        if (System.currentTimeMillis() > running.deadline) {
            finish(client, running, "the action took longer than " + running.ticks + " ticks");
            return;
        }

        try {
            if (running.work.isEmpty()) {
                finish(client, running, null);
                return;
            }
            AgentActions.Step step = running.work.get(0);

            // Steps that only touch the screen - pressing a button, waiting, a screenshot - also work before a
            // world is loaded, which is how an agent opens the game and walks itself into a save.
            if (!inWorld && step.needsWorld()) {
                finish(client, running, "the game is not in a world");
                return;
            }

            step.tick(client);
            if (step.finished()) {
                running.work.remove(0);
                running.report.getAsJsonArray("steps").add(step.report());
            }
        } catch (Throwable throwable) {
            MinecraftMcpClient.LOGGER.warn("An agent request failed", throwable);
            finish(client, running, throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    // ------------------------------------------------------------------ the REST half

    private static void act(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        try {
            JsonObject request = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
            JsonArray specs = request.has("steps") ? request.getAsJsonArray("steps") : new JsonArray();
            sendJson(exchange, 200, await(submit(AgentActions.parse(specs))));
        } catch (IllegalArgumentException | JsonParseException bad) {
            sendJson(exchange, 400, error(bad.getMessage()));
        }
    }

    private static JsonObject health() {
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", true);
        answer.addProperty("inWorld", inWorld);
        answer.addProperty("mcp", true);
        return answer;
    }

    // ------------------------------------------------------------------ plumbing

    private static void finish(Minecraft client, Task task, String problem) {
        AgentInput.release();
        task.report.addProperty("ok", problem == null);
        if (problem != null) {
            task.report.addProperty("error", problem);
        }
        task.report.add("state", AgentState.snapshot(client, STATE_RADIUS, CHAT));
        task.done.complete(task.report);
        running = null;
    }

    /** The job, as the agent sees it: null when nothing is running. */
    public static JsonObject jobStatus() {
        return job == null ? null : job.status(Minecraft.getInstance());
    }

    public static JsonObject error(String message) {
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", false);
        answer.addProperty("error", message == null ? "unknown error" : message);
        return answer;
    }

    public static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        sendBytes(exchange, status, "application/json; charset=utf-8", GSON.toJson(body));
    }

    public static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        sendBytes(exchange, status, "text/plain; charset=utf-8", body);
    }

    /** An empty body with a status: what MCP wants for a notification, which needs no answer. */
    public static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static void sendBytes(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** One request in flight: the steps left to run and the answer to hand back when they are all done. */
    private static final class Task {
        private final JsonObject report = new JsonObject();
        private final List<AgentActions.Step> work;
        private final int ticks;
        private final CompletableFuture<JsonObject> done = new CompletableFuture<>();
        private long deadline;

        private Task(List<AgentActions.Step> work, int ticks) {
            this.work = work;
            this.ticks = Math.max(1, ticks);
            this.report.add("steps", new JsonArray());
        }
    }
}
