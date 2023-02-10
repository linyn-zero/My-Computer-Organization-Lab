// Copyright 2021 Howard Lau
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package peripheral

import chisel3._
import chisel3.util._
import riscv.Parameters

object GlyphInfo {
  val glyphWidth = 8
  val glyphHeight = 16
  // ASCII printable characters start from here
  val spaceIndex = 1
}

object ScreenInfo {
  val DisplayHorizontal = 640
  val DisplayVertical = 480

  val CharCols = DisplayHorizontal / GlyphInfo.glyphWidth
  val CharRows = DisplayVertical / GlyphInfo.glyphHeight
  val Chars = CharCols * CharRows
}


class VGASync extends Module {
  val io = IO(new Bundle {
    val hsync = Output(Bool())
    val vsync = Output(Bool())
    val video_on = Output(Bool())
    val p_tick = Output(Bool())
    val f_tick = Output(Bool())
    val x = Output(UInt(10.W))
    val y = Output(UInt(10.W))
  })

  val DisplayHorizontal = ScreenInfo.DisplayHorizontal
  val DisplayVertical = ScreenInfo.DisplayVertical

  val BorderLeft = 48
  val BorderRight = 16
  val BorderTop = 10
  val BorderBottom = 33

  val RetraceHorizontal = 96
  val RetraceVertical = 2

  val MaxHorizontal = DisplayHorizontal + BorderLeft + BorderRight + RetraceHorizontal - 1
  val MaxVertical = DisplayVertical + BorderTop + BorderBottom + RetraceVertical - 1

  val RetraceHorizontalStart = DisplayHorizontal + BorderRight
  val RetraceHorizontalEnd = RetraceHorizontalStart + RetraceHorizontal - 1

  val RetraceVerticalStart = DisplayVertical + BorderBottom
  val RetraceVerticalEnd = RetraceVerticalStart + RetraceVertical - 1

  val pixel = RegInit(UInt(2.W), 0.U)
  val pixel_next = Wire(UInt(2.W))
  val pixel_tick = Wire(Bool())

  val v_count_reg = RegInit(UInt(10.W), 0.U)
  val h_count_reg = RegInit(UInt(10.W), 0.U)

  val v_count_next = Wire(UInt(10.W))
  val h_count_next = Wire(UInt(10.W))

  val vsync_reg = RegInit(Bool(), false.B)
  val hsync_reg = RegInit(Bool(), false.B)

  val vsync_next = Wire(Bool())
  val hsync_next = Wire(Bool())

  pixel_next := pixel + 1.U
  pixel_tick := pixel === 0.U

  h_count_next := Mux(
    pixel_tick,
    Mux(h_count_reg === MaxHorizontal.U, 0.U, h_count_reg + 1.U),
    h_count_reg
  )

  v_count_next := Mux(
    pixel_tick && h_count_reg === MaxHorizontal.U,
    Mux(v_count_reg === MaxVertical.U, 0.U, v_count_reg + 1.U),
    v_count_reg
  )

  hsync_next := h_count_reg >= RetraceHorizontalStart.U && h_count_reg <= RetraceHorizontalEnd.U
  vsync_next := v_count_reg >= RetraceVerticalStart.U && v_count_reg <= RetraceVerticalEnd.U

  pixel := pixel_next
  hsync_reg := hsync_next
  vsync_reg := vsync_next
  v_count_reg := v_count_next
  h_count_reg := h_count_next

  io.video_on := h_count_reg < DisplayHorizontal.U && v_count_reg < DisplayVertical.U
  io.hsync := hsync_reg
  io.vsync := vsync_reg
  io.x := h_count_reg
  io.y := v_count_reg
  io.p_tick := pixel_tick
  io.f_tick := io.x === 0.U && io.y === 0.U
}

class VGADisplay extends Module {
  val io = IO(new Bundle() {
    val bundle = new RAMBundle

    val hsync = Output(Bool())
    val vsync = Output(Bool())

    val rgb = Output(UInt(12.W))
  })
  val mem = Module(new BlockRAM(ScreenInfo.Chars / Parameters.WordSize))
  mem.io.write_enable := io.bundle.write_enable
  mem.io.write_data := io.bundle.write_data
  mem.io.write_address := io.bundle.write_data
  mem.io.write_strobe := io.bundle.write_strobe

  mem.io.read_address := io.bundle.address
  io.bundle.read_data := mem.io.read_data

  val sync = Module(new VGASync)
  io.hsync := sync.io.hsync
  io.vsync := sync.io.vsync

  val font_rom = Module(new FontROM)
  val row = (sync.io.y >> log2Up(GlyphInfo.glyphHeight)).asUInt
  val col = (sync.io.x >> log2Up(GlyphInfo.glyphWidth)).asUInt
  val char_index = (row * ScreenInfo.CharCols.U) + col
  val offset = char_index(1, 0)
  val ch = Wire(UInt(8.W))

  mem.io.debug_read_address := char_index
  ch := MuxLookup(
    offset,
    0.U,
    IndexedSeq(
      0.U -> mem.io.debug_read_data(7, 0).asUInt,
      1.U -> mem.io.debug_read_data(15, 8).asUInt,
      2.U -> mem.io.debug_read_data(23, 16).asUInt,
      3.U -> mem.io.debug_read_data(31, 24).asUInt
    )
  )
  font_rom.io.glyph_index := Mux(ch >= 32.U, ch - 31.U, 0.U)
  font_rom.io.glyph_y := sync.io.y(log2Up(GlyphInfo.glyphHeight) - 1, 0)

  // White if pixel_on and glyph pixel on
  val glyph_x = sync.io.x(log2Up(GlyphInfo.glyphWidth) - 1, 0)
  io.rgb := Mux(sync.io.video_on && font_rom.io.glyph_pixel_byte(glyph_x), 0xFFFF.U, 0.U)
}
