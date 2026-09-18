package dev.aegis.assistant.adapters.claudecode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.TreeSet;

/**
 * Controlled stand-in for the Claude Code CLI. The first argument is a file where it records what it received
 * (arguments, environment names, token presence, working directory, prompt); the behavior is chosen by the
 * {@code --model} value. Never contacts a network.
 */
public final class FakeClaudeCli {
    static final String EXPECTED_TOKEN = "sk-ant-oat01-fake-subscription-token";

    private FakeClaudeCli() {
    }

    public static void main(String[] args) throws Exception {
        Path record = Path.of(args[0]);
        List<String> cli = List.of(args).subList(1, args.length);
        String model = cli.get(cli.indexOf("--model") + 1);
        String prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        Path cwd = Path.of("").toAbsolutePath();
        String home = System.getenv("HOME");
        boolean tokenOk = EXPECTED_TOKEN.equals(System.getenv(ClaudeCodeGateway.TOKEN_ENV));
        String arguments = String.join("<ARG>", cli).replace(System.lineSeparator(), "<NL>").replace("\n", "<NL>");
        String report = "args=" + arguments + "\nenv=" + new TreeSet<>(System.getenv().keySet())
                + "\ntokenOk=" + tokenOk + "\ncwd=" + cwd + "\nhome=" + home
                + "\ncwdWasEmpty=" + isEmpty(cwd) + "\nprompt=" + prompt + "\n";
        Files.writeString(record, report, StandardCharsets.UTF_8);
        String usage = "\"usage\":{\"input_tokens\":900,\"output_tokens\":12,\"cache_read_input_tokens\":0,"
                + "\"cache_creation_input_tokens\":0},\"modelUsage\":{\"claude-haiku-4-5-20251001\":{}}";
        switch (model) {
            case "ok" -> out("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                    + "\"result\":\"{\\\"status\\\":\\\"ok\\\"}\",\"structured_output\":{\"status\":\"ok\"},"
                    + "\"total_cost_usd\":0.001," + usage + "}", 0);
            case "text-json" -> out("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                    + "\"result\":\"{\\\"status\\\":\\\"ok\\\"}\"," + usage + "}", 0);
            // Messages observed from Claude Code 2.1.276 without a token and with an invalid token.
            case "login" -> out("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,"
                    + "\"result\":\"Failed to authenticate. API Error: 401 OAuth access token is invalid.\"}", 1);
            case "not-logged-in" -> out("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,"
                    + "\"result\":\"Not logged in · Please run /login\"}", 1);
            case "limit" -> out("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,"
                    + "\"result\":\"Claude AI usage limit reached|" + (Instant.now().getEpochSecond() + 3600)
                    + "\"}", 1);
            case "missing-model" -> out("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,"
                    + "\"result\":\"API Error: 404 model: claude-x not found\"}", 1);
            case "max-turns" -> out("{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true,"
                    + "\"result\":\"\"," + usage + "}", 1);
            case "garbage" -> out("this is not json", 0);
            case "crash" -> {
                System.err.println("Error: the runtime exploded");
                System.exit(2);
            }
            case "echo-token" -> out("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,"
                    + "\"result\":\"server said " + System.getenv(ClaudeCodeGateway.TOKEN_ENV) + "\"}", 1);
            case "slow" -> {
                Thread.sleep(20_000);
                out("{}", 0);
            }
            default -> out("{}", 3);
        }
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    private static void out(String json, int exit) {
        System.out.println(json);
        System.out.flush();
        System.exit(exit);
    }
}
