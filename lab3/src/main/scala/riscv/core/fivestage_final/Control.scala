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

package riscv.core.fivestage_final

import chisel3._
import riscv.Parameters

class Control extends Module {
  val io = IO(new Bundle {
    val jump_flag = Input(Bool())             // 正式的跳转，综合id和clint
    val jump_instruction_id = Input(Bool())   // id为跳转指令
    val rs1_id = Input(UInt(Parameters.PhysicalRegisterAddrWidth))
    val rs2_id = Input(UInt(Parameters.PhysicalRegisterAddrWidth))
    val memory_read_enable_ex = Input(Bool())
    val reg_write_enable_ex = Input(Bool())
    val rd_ex = Input(UInt(Parameters.PhysicalRegisterAddrWidth))
    val memory_read_enable_mem = Input(Bool())
    val reg_write_enable_mem = Input(Bool())
    val rd_mem = Input(UInt(Parameters.PhysicalRegisterAddrWidth))

    val if2id_flush = Output(Bool())
    val id2ex_flush = Output(Bool())
    val pc_stall = Output(Bool())
    val if2id_stall = Output(Bool())
  })

  // Lab3(Final)
  io.if2id_flush := false.B
  io.id2ex_flush := false.B
  io.pc_stall := false.B
  io.if2id_stall := false.B
/**
 * 还是阻塞和跳转清空的老问题：
 * 两者的判断时机都是指令位于ID段时：
 * 1. 若是非跳转/分支指令数据冲突，此时不会判断跳转
 * 2. 若是跳转/分支指令数据冲突，要优先解决数据冲突
 * 3. 若是非跳转/分支指令数据冲突，且此时中断跳转，此时应该阻塞if2id（会因此阻塞IntFlag）
 * 4. 若是若是跳转/分支指令数据冲突，且此时中断跳转，此时应该阻塞if2id直到数据冲突解决（会因此阻塞IntFlag）
 */
  // 无论当前指令，上条指令为load且RAW则阻塞
//  when(io.memory_read_enable_ex && io.reg_write_enable_ex && io.rd_ex =/= 0.U
//    && (io.rs1_id === io.rd_ex || io.rs2_id === io.rd_ex)) {
//    io.pc_stall := true.B
//    io.if2id_stall := true.B
//    io.id2ex_flush := true.B
//    // 这里已经包括了第3条的情况了
//  }
//  // 当前为跳转指令,且RAW,且紧邻load，则阻塞第2次。与上一情况互斥
//  .elsewhen(io.jump_instruction_id && io.memory_read_enable_mem
//    && io.reg_write_enable_mem && io.rd_mem =/= 0.U
//    && (io.rs1_id === io.rd_mem || io.rs2_id === io.rd_mem)) {
//    io.pc_stall := true.B
//    io.if2id_stall := true.B
//    io.id2ex_flush := true.B
//    // 这里已经包括了第4条的情况了
//  }
//  // 当前为跳转指令,且RAW,则阻塞1次。与上一情况互斥
//  .elsewhen(io.jump_instruction_id && io.reg_write_enable_ex && io.rd_ex =/= 0.U
//    && (io.rs1_id === io.rd_ex || io.rs2_id === io.rd_ex)) {
//    io.pc_stall := true.B
//    io.if2id_stall := true.B
//    io.id2ex_flush := true.B
//    // 这里已经包括了第4
//    // 条的情况了
//  }
//  // 正式的跳转指令一定是没有冒险时才算数。有冒险时jump_flag可能是假的
//  // 跳转时，ID气泡！！！！
//  .elsewhen(io.jump_flag) {
//    io.if2id_flush := true.B
//  }
  // 化简结果
  private val DataHazard_ex: Bool = io.reg_write_enable_ex && io.rd_ex =/= 0.U && (io.rs1_id === io.rd_ex || io.rs2_id === io.rd_ex)
  private val DataHazard_mem: Bool = io.reg_write_enable_mem && io.rd_mem =/= 0.U && (io.rs1_id === io.rd_mem || io.rs2_id === io.rd_mem)
  private val stall_flag: Bool = io.memory_read_enable_ex && DataHazard_ex ||
                   io.jump_instruction_id && io.memory_read_enable_mem && DataHazard_mem ||
                   io.jump_instruction_id && DataHazard_ex // (ab+cde+db = 化简不了)
  // 四个控制信号
  io.pc_stall := stall_flag
  io.if2id_stall := stall_flag
  io.id2ex_flush := stall_flag
  io.if2id_flush := !stall_flag && io.jump_flag

  // Lab3(Final) End
}
