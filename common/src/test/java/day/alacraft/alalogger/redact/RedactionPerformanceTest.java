package day.alacraft.alalogger.redact;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A budget test, not a microbenchmark.
 *
 * <p>The API accepts logs up to 10 MiB and this runs inside the game, so the
 * question that matters is whether a worst-case file stalls the server for a
 * noticeable number of ticks. The generous ceiling is on purpose: the number to
 * defend is "not seconds", and a tight assertion on shared CI hardware would fail
 * for reasons that have nothing to do with the code.
 *
 * <p>What it really guards against is catastrophic backtracking. A pattern that
 * degrades quadratically does not come in at six seconds instead of four — it
 * comes in at several minutes, or never.
 */
class RedactionPerformanceTest {

    private static final int TARGET_BYTES = 8 * 1024 * 1024;

    private static final long BUDGET_MILLIS = 5_000;

    /**
     * Limits raised well past the defaults on purpose: with the shipping limits
     * the pipeline would truncate to 25 000 lines and then measure a tenth of the
     * work, which is the opposite of what this test is for.
     */
    private final Redactor redactor =
            new Redactor(Redactor.defaultRules(), 64 * 1024 * 1024, 5_000_000);

    @Test
    void cleansAnEightMegabyteLogWellInsideTheBudget() {
        String log = buildLog(TARGET_BYTES);
        int megabytes = log.getBytes(StandardCharsets.UTF_8).length / (1024 * 1024);

        // One untimed pass over a small slice so the measured run is not paying
        // for JIT compilation of the matcher loops.
        redactor.redact(log.substring(0, Math.min(log.length(), 256 * 1024)));

        long startedAt = System.nanoTime();
        RedactionResult result = redactor.redact(log);
        long millis = (System.nanoTime() - startedAt) / 1_000_000;

        System.out.printf(
                "redacted %d MiB / %d lines in %d ms (%s)%n",
                megabytes, result.lines(), millis, result.matchedSummary());

        assertTrue(result.totalRedactions() > 0, "the fixture must actually exercise the rules");
        assertTrue(millis < BUDGET_MILLIS,
                "redaction took " + millis + " ms for " + megabytes + " MiB, budget is " + BUDGET_MILLIS + " ms");
    }

    @Test
    void doesNotBlowUpOnALongRunOfBase64() {
        // The shape that kills a naive secret pattern: one enormous token with no
        // separators, so every candidate start position has to be retried.
        String log = "chunk data: " + "QWxhQ3JhZnRMb2dnZXI".repeat(50_000);

        long startedAt = System.nanoTime();
        new Redactor().redact(log);
        long millis = (System.nanoTime() - startedAt) / 1_000_000;

        System.out.printf("redacted a %d KiB base64 run in %d ms%n", log.length() / 1024, millis);
        assertTrue(millis < BUDGET_MILLIS, "base64 run took " + millis + " ms");
    }

    @Test
    void doesNotBlowUpOnALongRunOfHex() {
        // The other shape: a memory dump pasted without the hs_err header, so the
        // dump rule does not fire and every pattern sees the hex.
        StringBuilder log = new StringBuilder("Instructions:\n");
        for (int i = 0; i < 40_000; i++) {
            log.append("0x00007ff8a1b2c3d4:   48 89 e5 41 57 41 56 41 55 41 54 53 48 83 ec 28\n");
        }

        long startedAt = System.nanoTime();
        new Redactor().redact(log.toString());
        long millis = (System.nanoTime() - startedAt) / 1_000_000;

        System.out.printf("redacted a %d KiB hex dump in %d ms%n", log.length() / 1024, millis);
        assertTrue(millis < BUDGET_MILLIS, "hex dump took " + millis + " ms");
    }

    /**
     * A log shaped like the real thing: a modded server's chatter, stack traces,
     * a mod table, an hs_err block with memory dumps, and — sparsely, the way it
     * actually happens — the sensitive values.
     */
    private static String buildLog(int targetBytes) {
        StringBuilder log = new StringBuilder(targetBytes + 4096);

        log.append("# A fatal error has been detected by the Java Runtime Environment:\n")
                .append("# Problematic frame:\n")
                .append("# C  [nvoglv64.dll+0x8f4a2b]\n")
                .append("Command Line: net.minecraft.client.main.Main --username Alex")
                .append(" --accessToken eyJhbGciOiJIUzI1NiJ9.eyJ4dWlkIjoiMjUzNSJ9.dBjftJeZ4CVPmB92K27uhb")
                .append(" --xuid 2535412345678901\n");

        int block = 0;
        while (log.length() < targetBytes) {
            log.append("[12:0").append(block % 10).append(":00] [Server thread/INFO]: ")
                    .append("Preparing spawn area: ").append(block % 100).append("%\n");
            log.append("[12:00:00] [Server thread/INFO]: Player").append(block)
                    .append("[/203.0.113.").append(block % 250).append(":51234] logged in\n");
            log.append("\tat TRANSFORMER/neoforge@26.1.2.28-beta/net.neoforged.neoforge.event.EventHooks")
                    .append(".onEntityTick(EventHooks.java:").append(block % 900).append(")\n");
            log.append("\tat java.base@25.0.2/java.lang.Thread.run(Thread.java:1583)\n");
            log.append("\t\tjei-15.2.0.27.jar   |Just Enough Items   |jei   |15.2.0.27   |Manifest: ")
                    .append("fe6d8f0df30db96d6feebd335c3f76cee7ce517fe279847d69b474127c8aba4a\n");
            log.append("\tLoading C:\\Users\\Steve\\AppData\\Roaming\\.minecraft\\mods\\mod")
                    .append(block).append(".jar\n");

            // The memory dump the whole file is dangerous for, repeated so the
            // structural rule does real work rather than firing once.
            log.append("Registers:\n");
            for (int line = 0; line < 8; line++) {
                log.append("RAX=0x000000000000000").append(line)
                        .append(", RBX=0x000001f4c0d3e5a0, RCX=0x000001f4c0d3e5b0\n");
            }
            log.append('\n');

            block++;
        }

        log.append("\tWorld Seed: 7026191857309640518\n")
                .append("USERNAME=Steve\n")
                .append("HOME=/home/steve\n")
                .append("contact: someone@example.org\n")
                .append("END.\n");

        return log.toString();
    }
}
