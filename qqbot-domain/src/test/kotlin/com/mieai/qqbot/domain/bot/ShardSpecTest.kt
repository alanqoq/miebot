package com.mieai.qqbot.domain.bot

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ShardSpecTest {
    @Test
    fun representsSingleAndMultiShardAssignments() {
        assertThat(ShardSpec.single()).isEqualTo(ShardSpec(0, 1))
        assertThat(ShardSpec(2, 4).index).isEqualTo(2)
        assertThat(ShardSpec(2, 4).count).isEqualTo(4)
    }

    @ParameterizedTest
    @CsvSource("-1, 1", "0, 0", "0, -1", "1, 1", "2, 1")
    fun rejectsInvalidShardCombinations(index: Int, count: Int) {
        assertThatIllegalArgumentException().isThrownBy { ShardSpec(index, count) }
    }
}
