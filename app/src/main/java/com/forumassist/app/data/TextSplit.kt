package com.forumassist.app.data

/**
 * 长正文拆分：把一楼的块列表按空行/长度切成若干段。
 *
 * 为什么要拆：Compose 的 Text 排版是 O(字数) 的，几万字塞进一个 Text，
 * 首帧和滚动都会明显卡顿；拆成 600 字左右的小块后，LazyColumn 只排版可视区域。
 *
 * 这个函数会在大正文上跑，务必在 IO / Default 线程调用，不要放组合里。
 */
fun splitBlocks(blocks: List<Block>, maxChars: Int = 600): List<Block> {
    val out = ArrayList<Block>(blocks.size + 16)
    for (block in blocks) {
        if (block !is Block.Text) {
            out.add(block)
            continue
        }
        val buf = StringBuilder()
        for (rawLine in block.text.split('\n')) {
            // Discuz 用 &nbsp; 做首行缩进，NBSP 不属于 Kotlin 的 trim 范围，先换成普通空格
            val line = rawLine.replace('\u00A0', ' ').trim()
            if (line.isEmpty()) {
                if (buf.isNotEmpty()) {
                    out.add(Block.Text(buf.toString()))
                    buf.setLength(0)
                }
                continue
            }
            if (buf.length + line.length > maxChars) {
                out.add(Block.Text(buf.toString()))
                buf.setLength(0)
            }
            if (buf.isNotEmpty()) buf.append('\n')
            buf.append(line)
        }
        if (buf.isNotEmpty()) out.add(Block.Text(buf.toString()))
    }
    return out
}

/** 给详情补上预先拆好的段落（已算过就原样返回） */
fun ThreadDetail.withSections(): ThreadDetail =
    if (sections.size == posts.size) this
    else copy(sections = posts.map { splitBlocks(it.blocks) })
