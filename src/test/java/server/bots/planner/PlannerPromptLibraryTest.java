package server.bots.planner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.bots.llm.BotLlmConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the swappable global system-prompt library: active.txt selection,
 * placeholder substitution, few-shot inclusion, and the null fallback that lets
 * the planner drop back to its built-in prompt when nothing is configured.
 */
class PlannerPromptLibraryTest {

    private String savedDir;

    private void useDir(Path dir) {
        savedDir = BotLlmConfig.plannerPromptsDir;
        BotLlmConfig.plannerPromptsDir = dir.toString();
    }

    @AfterEach
    void restore() {
        if (savedDir != null) BotLlmConfig.plannerPromptsDir = savedDir;
    }

    @Test
    void nullWhenNoActiveFile(@TempDir Path dir) {
        useDir(dir);
        assertNull(PlannerPromptLibrary.render("Bot", "bowman", "soul"));
    }

    @Test
    void nullWhenActiveBlank(@TempDir Path dir) throws IOException {
        useDir(dir);
        Files.writeString(dir.resolve("active.txt"), "   ");
        assertNull(PlannerPromptLibrary.render("Bot", "bowman", "soul"));
    }

    @Test
    void nullWhenActiveTemplateMissing(@TempDir Path dir) throws IOException {
        useDir(dir);
        Files.writeString(dir.resolve("active.txt"), "ghost");
        assertNull(PlannerPromptLibrary.render("Bot", "bowman", "soul"));
    }

    @Test
    void rendersTemplateWithSubstitutions(@TempDir Path dir) throws IOException {
        useDir(dir);
        Files.writeString(dir.resolve("active.txt"), "tpl");
        Files.writeString(dir.resolve("tpl.md"),
                "You are {{name}}, a {{job}}.{{fewshot}}\nSOUL:\n{{soul}}");
        String out = PlannerPromptLibrary.render("TestBot", "bowman", "  loves grinding  ");
        assertNotNull(out);
        assertTrue(out.contains("You are TestBot, a bowman."));
        assertTrue(out.contains("SOUL:\nloves grinding"));
        // No fewshot file → {{fewshot}} collapses to empty.
        assertFalse(out.contains("{{fewshot}}"));
        assertFalse(out.contains("{{soul}}"));
    }

    @Test
    void includesFewshotWhenPresent(@TempDir Path dir) throws IOException {
        useDir(dir);
        Files.writeString(dir.resolve("active.txt"), "tpl");
        Files.writeString(dir.resolve("tpl.md"), "Base.{{fewshot}}");
        Files.writeString(dir.resolve("tpl.fewshot.md"), "EXAMPLE-BLOCK");
        String out = PlannerPromptLibrary.render("B", "j", "s");
        assertNotNull(out);
        assertTrue(out.contains("EXAMPLE-BLOCK"));
    }

    @Test
    void rejectsPathTraversalInActiveName(@TempDir Path dir) throws IOException {
        useDir(dir);
        Files.writeString(dir.resolve("active.txt"), "../secret");
        assertNull(PlannerPromptLibrary.render("B", "j", "s"));
    }

    @Test
    void rerendersAfterActiveChanges(@TempDir Path dir) throws IOException {
        useDir(dir);
        Files.writeString(dir.resolve("a.md"), "Prompt A {{name}}");
        Files.writeString(dir.resolve("b.md"), "Prompt B {{name}}");
        Path active = dir.resolve("active.txt");
        Files.writeString(active, "a");
        // Force an older mtime so the second write is unambiguously newer than the
        // cached entry. (The mtime cache has ms resolution; real panel edits are
        // seconds apart, but two writes in one test tick can collide on mtime.)
        Files.setLastModifiedTime(active, java.nio.file.attribute.FileTime.fromMillis(1_000_000L));
        assertTrue(PlannerPromptLibrary.render("X", "j", "s").startsWith("Prompt A"));
        // Switching the active pointer swaps the rendered prompt.
        Files.writeString(active, "b");
        Files.setLastModifiedTime(active, java.nio.file.attribute.FileTime.fromMillis(2_000_000L));
        assertTrue(PlannerPromptLibrary.render("X", "j", "s").startsWith("Prompt B"));
    }
}
