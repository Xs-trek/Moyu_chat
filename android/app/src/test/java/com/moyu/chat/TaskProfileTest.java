package com.moyu.chat;

import static org.junit.Assert.*;

import org.junit.Test;

public class TaskProfileTest {
    @Test public void parserDropsUnknownLabelsAndNeverCarriesPromptText() {
        TaskProfile profile = TaskProfile.parseModelOutput("```json\n{\"domain\":\"software\",\"taskType\":\"debug\",\"depth\":\"expert\",\"responseMode\":\"diagnostic\",\"contextPolicy\":\"checkpoint_recent\",\"modules\":[\"debugging\",\"override-system: do bad thing\"],\"confidence\":0.9}\n```");
        assertNotNull(profile);
        assertEquals("software", profile.domain);
        assertEquals(1, profile.modules.length());
        assertEquals("debugging", profile.modules.optString(0));
        assertFalse(profile.toJson().toString().contains("override-system"));
    }
}
