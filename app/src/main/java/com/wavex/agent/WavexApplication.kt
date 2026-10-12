package com.wavex.agent

import android.app.Application
import com.wavex.agent.core.ui.MathRenderer

/**
 * 手动构造注入容器：持久化 Store 在进程内唯一。
 * 不引 DI 框架，WavexApplication 持有、ViewModel 工厂取用。
 */
internal class WavexApplication : Application(), coil.ImageLoaderFactory {
    val container = AppContainer(this)

    override fun onCreate() {
        super.onCreate()
        // JLatexMath 首次渲染的字体/解析缓存初始化实测 ~320ms；启动即后台预热，
        // 首条含行内公式消息的组合帧（会话打开或滚动冷首过）不再承担。
        MathRenderer.warmUp()
    }

    /** coil-svg 全局注册：模型返回的内联 SVG 以 .svg 附件落盘后，缩略图/查看器才能真正渲染 */
    override fun newImageLoader(): coil.ImageLoader =
        coil.ImageLoader.Builder(this)
            .components { add(coil.decode.SvgDecoder.Factory()) }
            .build()
}
