package community.flock.wirespec.extractor.ast

import community.flock.wirespec.compiler.core.parse.ast.Channel as WsChannel
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.extractor.model.Channel
import community.flock.wirespec.extractor.model.WireType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class WirespecAstBuilderChannelTest {

    private val builder = WirespecAstBuilder()

    @Test
    fun `channel with custom payload reference`() {
        val ch = Channel(
            ownerSimpleName = "OrderConsumer",
            name = "OnOrderCreated",
            payload = WireType.Ref("OrderEvent"),
        )

        val ws = builder.toChannel(ch)

        ws.shouldBeInstanceOf<WsChannel>()
        ws.identifier.value shouldBe "OnOrderCreated"
        ws.annotations shouldBe emptyList()
        ws.reference.shouldBeInstanceOf<Reference.Custom>()
        (ws.reference as Reference.Custom).value shouldBe "OrderEvent"
    }

    @Test
    fun `channel with primitive payload reference`() {
        val ch = Channel(
            ownerSimpleName = "X",
            name = "OnPing",
            payload = WireType.Primitive(WireType.Primitive.Kind.STRING),
        )

        val ws = builder.toChannel(ch)
        ws.reference.shouldBeInstanceOf<Reference.Primitive>()
    }
}
