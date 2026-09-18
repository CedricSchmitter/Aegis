package dev.aegis.archfixture.otherctx.adapters;

import java.io.IOException;

/** Fixture: an adapter that would start a shell, for example to run a model CLI. Must be rejected by the rules. */
public class ShellingOutAdapter {
    public Process run(String command) throws IOException {
        return new ProcessBuilder("sh", "-c", command).start();
    }
}
