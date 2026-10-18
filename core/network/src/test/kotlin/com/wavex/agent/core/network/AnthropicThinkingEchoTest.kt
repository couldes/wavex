package com.wavex.agent.core.network

import com.wavex.agent.core.domain.model.ChatRequestMessage
import com.wavex.agent.core.domain.model.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anthropic 协议在工具轮次上有一条硬性要求（真机实测抓出来的）：
 * 模型带了 thinking 的 assistant 消息，回填 tool_result 那一轮必须把同一个
 * thinking 块原样传回去，否则上游 400
 * 「The content[].thinking in the thinking mode must be passed back to the API」。
 *
 * 只要 assistant 消息里出现 tool_use 就会触发这条校验；DeepSeek 的 /anthropic
 * 端点默认就是 thinking 模式，所以即使 App 没开思考等级也会撞上。
 */
class AnthropicThinkingEchoTest {

    private val call = ToolCall("toolu_1", "web_search", "{\"query\":\"btc\"}", "btc")

    @Test
    fun `assistant thinking is emitted as a thinking block before text and tool_use`() {
        val payload = RequestPayloads.anthropicPayload(
            model = "claude-x",
            history = listOf(
                ChatRequestMessage("user", "比特币现在多少？"),
                ChatRequestMessage(
                    "assistant", "我查一下",
                    toolCalls = listOf(call),
                    thinking = "用户要价格，先搜索",
                    thinkingSignature = "sig_abc",
                ),
                ChatRequestMessage("tool", "{\"items\":[]}", toolCallId = "toolu_1"),
            ),
            reasoningEffort = null,
            webSearch = true,
        )
        val blocks = payload.getJSONArray("messages")
            .getJSONObject(1).getJSONArray("content")
        val types = (0 until blocks.length()).map { blocks.getJSONObject(it).getString("type") }
        // Anthropic 要求顺序：thinking → text → tool_use
        assertEquals(listOf("thinking", "text", "tool_use"), types)
        val thinking = blocks.getJSONObject(0)
        assertEquals("用户要价格，先搜索", thinking.getString("thinking"))
        assertEquals("sig_abc", thinking.getString("signature"))
    }

    @Test
    fun `missing signature is serialized as empty string, not omitted`() {
        val payload = RequestPayloads.anthropicPayload(
            model = "claude-x",
            history = listOf(
                ChatRequestMessage(
                    "assistant", "", toolCalls = listOf(call), thinking = "thinking text"
                ),
                ChatRequestMessage("tool", "{}", toolCallId = "toolu_1"),
            ),
            reasoningEffort = null,
            webSearch = true,
        )
        val block = payload.getJSONArray("messages").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0)
        assertEquals("thinking", block.getString("type"))
        assertTrue("signature 字段必须存在（中转站按字段存在性校验）", block.has("signature"))
        assertEquals("", block.getString("signature"))
    }

    @Test
    fun `no thinking block when the message has no thinking`() {
        val payload = RequestPayloads.anthropicPayload(
            model = "claude-x",
            history = listOf(
                ChatRequestMessage("assistant", "答案", toolCalls = listOf(call)),
                ChatRequestMessage("tool", "{}", toolCallId = "toolu_1"),
            ),
            reasoningEffort = null,
            webSearch = true,
        )
        val blocks = payload.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        val types = (0 until blocks.length()).map { blocks.getJSONObject(it).getString("type") }
        assertEquals(listOf("text", "tool_use"), types)
    }

    /** 非工具轮的普通历史不该凭空长出 thinking 块（会白烧 token） */
    @Test
    fun `assistant text only history unchanged`() {
        val payload = RequestPayloads.anthropicPayload(
            model = "claude-x",
            history = listOf(ChatRequestMessage("assistant", "纯文本回答")),
            reasoningEffort = null,
            webSearch = false,
        )
        val content = payload.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
    }

    /** OpenAI 协议不认 thinking 块；思考内容另有 reasoning_content 通道，别混进来 */
    @Test
    fun `openai payload ignores thinking field`() {
        val payload = RequestPayloads.openAiPayload(
            model = "gpt-x",
            history = listOf(ChatRequestMessage(
                "assistant", "正文", thinking = "内部思考", toolCalls = listOf(call)
            )),
            reasoningEffort = null,
            webSearch = true,
        )
        val msg = JSONObject(payload.toString()).getJSONArray("messages").getJSONObject(0)
        assertTrue("OpenAI 历史里不该出现 thinking", msg.optString("thinking").isBlank())
        assertEquals("正文", msg.getString("content"))
    }
}
