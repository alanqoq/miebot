package com.mieai.qqbot.domain.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ShardSpecTest {
    @Test
    void representsSingleAndMultiShardAssignments() {
        assertThat(ShardSpec.single()).isEqualTo(new ShardSpec(0, 1));
        assertThat(new ShardSpec(2, 4).index()).isEqualTo(2);
        assertThat(new ShardSpec(2, 4).count()).isEqualTo(4);
    }

    @ParameterizedTest
    @CsvSource({"-1, 1", "0, 0", "0, -1", "1, 1", "2, 1"})
    void rejectsInvalidShardCombinations(int index, int count) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ShardSpec(index, count));
    }
}
