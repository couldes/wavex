package com.wavex.agent

import android.app.Application

/**
 * 手动构造注入容器：持久化 Store 在进程内唯一。
 * 不引 DI 框架，WavexApplication 持有、ViewModel 工厂取用。
 */
internal class WavexApplication : Application(), coil.ImageLoaderFactory {
    val container = AppContainer(this)

    /** coil-svg 全局注册：模型返回的内联 SVG 以 .svg 附件落盘后，缩略图/查看器才能真正渲染 */
    override fun newImageLoader(): coil.ImageLoader =
        coil.ImageLoader.Builder(this)
            .components { add(coil.decode.SvgDecoder.Factory()) }
            .build()
}
