package com.zhiyun;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyun.workflow.PatchApplier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PatchApplierTest {
    @Test
    void prefersOffsetWhenSliceMatches() {
        String text = "foo bar foo";
        PatchApplier.Spec spec = new PatchApplier.Spec(8, 11, "foo", "baz");
        assertThat(PatchApplier.applyOnce(text, spec)).isEqualTo("foo bar baz");
    }

    @Test
    void fallsBackToSingleIndexOfWhenOffsetMismatches() {
        String text = "foo bar foo";
        PatchApplier.Spec spec = new PatchApplier.Spec(4, 7, "foo", "baz");
        assertThat(PatchApplier.applyOnce(text, spec)).isEqualTo("baz bar foo");
    }

    @Test
    void doesNotReplaceAllOccurrences() {
        String text = "alpha alpha";
        PatchApplier.Result result = PatchApplier.applyAll(text, List.of(PatchApplier.Spec.of("alpha", "beta")));
        assertThat(result.text()).isEqualTo("beta alpha");
        assertThat(result.applied()).isEqualTo(1);
    }

    @Test
    void appliesMultipleFromTheEndSoEarlierOffsetsStayValid() {
        String text = "one two three";
        PatchApplier.Spec a = new PatchApplier.Spec(0, 3, "one", "1");
        PatchApplier.Spec b = new PatchApplier.Spec(4, 7, "two", "2");
        PatchApplier.Result result = PatchApplier.applyAll(text, List.of(a, b));
        assertThat(result.text()).isEqualTo("1 2 three");
        assertThat(result.applied()).isEqualTo(2);
    }

    @Test
    void readsLocationFromJson() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode patch = mapper.createObjectNode();
        patch.put("originalText", "old");
        patch.put("proposedText", "new");
        patch.putObject("location").put("startOffset", 4).put("endOffset", 7);
        PatchApplier.Spec spec = PatchApplier.fromJson(patch);
        assertThat(PatchApplier.applyOnce("say old now", spec)).isEqualTo("say new now");
    }
}
