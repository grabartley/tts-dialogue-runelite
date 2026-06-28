package com.grahambartley.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** The runtime learned-NPC cache: in-memory lookup plus atomic persistence across instances. */
public class LearnedNpcStoreTest {

  private final Gson gson = new Gson();

  @Test
  public void learnsAndReadsBackWithEthnicity() throws Exception {
    Path file = Files.createTempDirectory("learned").resolve("learned-npcs.json");
    LearnedNpcStore store = new LearnedNpcStore(file, gson);

    store.learn(123, "Dwarf", "Female", "kharidian");

    NPCAttributes a = store.get(123);
    assertEquals("Dwarf", a.getRace());
    assertEquals("Female", a.getGender());
    assertEquals("kharidian", a.getEthnicity());
    assertEquals("Learned", a.getSource());
    assertEquals(123, a.getNpcId());
    assertNull("an unlearned id is absent", store.get(999));
  }

  @Test
  public void persistsAcrossInstances() throws Exception {
    Path file = Files.createTempDirectory("learned").resolve("learned-npcs.json");
    new LearnedNpcStore(file, gson).learn(456, "Human", "Male", null);

    LearnedNpcStore reloaded = new LearnedNpcStore(file, gson);
    NPCAttributes a = reloaded.get(456);
    assertEquals("Human", a.getRace());
    assertEquals("Male", a.getGender());
    assertNull("a null ethnicity is not persisted", a.getEthnicity());
    assertEquals(1, reloaded.size());
  }

  @Test
  public void missingFileStartsEmpty() throws Exception {
    Path file = Files.createTempDirectory("learned").resolve("does-not-exist.json");
    assertEquals(0, new LearnedNpcStore(file, gson).size());
  }
}
