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

package riscv.core.fivestage_forward

import chisel3._
import chisel3.util.MuxLookup
import riscv.Parameters

object InterruptStatus {
  val None = 0x0.U(8.W)
  val Timer0 = 0x1.U(8.W)
  val Ret = 0xFF.U(8.W)
}

class CSRDirectAccessBundle extends Bundle {
  val mstatus = Input(UInt(Parameters.DataWidth))
  val mepc = Input(UInt(Parameters.DataWidth))
  val mcause = Input(UInt(Parameters.DataWidth))
  val mtvec = Input(UInt(Parameters.DataWidth))

  val mstatus_write_data = Output(UInt(Parameters.DataWidth))
  val mepc_write_data = Output(UInt(Parameters.DataWidth))
  val mcause_write_data = Output(UInt(Parameters.DataWidth))

  val direct_write_enable = Output(Bool())
}

// Core Local Interrupt Controller
class CLINT extends Module {
  val io = IO(new Bundle {
    val interrupt_flag = Input(UInt(Parameters.InterruptFlagWidth))

    val instruction_ex = Input(UInt(Parameters.InstructionWidth))
    val instruction_address_if = Input(UInt(Parameters.AddrWidth))
    val instruction_address_id = Input(UInt(Parameters.AddrWidth))

    val jump_flag = Input(Bool())
    val jump_address = Input(UInt(Parameters.AddrWidth))

    val ex_interrupt_handler_address = Output(UInt(Parameters.AddrWidth))
    val ex_interrupt_assert = Output(Bool())

    val csr_bundle = new CSRDirectAccessBundle
  })
  val interrupt_enable = io.csr_bundle.mstatus(3)
  val jumpping = RegNext(io.jump_flag || io.ex_interrupt_assert) //  告诉CLINT上一周期是否有“中断跳转”,这决定着此时ID是否气泡
  val instruction_address // 中断跳转的返回地址
  = Mux(
    io.jump_flag,    // 来自EX模块的跳转信号
    io.jump_address, // 与跳转目标地址
    Mux(
      jumpping,           // ID是否气泡
      io.instruction_address_if,  // EX没有跳转信号，且上一周期有中断跳转，这次中断跳转保存现在pc的指令地址（即上一周期中断跳转的目标地址）
      io.instruction_address_id   // EX没有跳转信号，且上一周期没有中断跳转，这次中断跳转保存现在ID的指令地址（即依赖指令）
    )
  )
  val mstatus_disable_interrupt = io.csr_bundle.mstatus(31, 4) ## 0.U(1.W) ## io.csr_bundle.mstatus(2, 0)
  val mstatus_recover_interrupt = io.csr_bundle.mstatus(31, 4) ## io.csr_bundle.mstatus(7) ## io.csr_bundle.mstatus(2, 0)

  //
  when(io.instruction_ex === InstructionsEnv.ecall || io.instruction_ex === InstructionsEnv.ebreak) {
    io.csr_bundle.mstatus_write_data := mstatus_disable_interrupt
    io.csr_bundle.mepc_write_data := instruction_address
    io.csr_bundle.mcause_write_data := MuxLookup(
      io.instruction_ex,
      10.U,
      IndexedSeq(
        InstructionsEnv.ecall -> 11.U,
        InstructionsEnv.ebreak -> 3.U,
      )
    )
    io.csr_bundle.direct_write_enable := true.B
    io.ex_interrupt_assert := true.B
    io.ex_interrupt_handler_address := io.csr_bundle.mtvec
  }
  // 进入中断处理程序
  .elsewhen(io.interrupt_flag =/= InterruptStatus.None && interrupt_enable) {
    io.csr_bundle.mstatus_write_data := mstatus_disable_interrupt
    io.csr_bundle.mepc_write_data := instruction_address
    io.csr_bundle.mcause_write_data := Mux(io.interrupt_flag(0), 0x80000007L.U, 0x8000000BL.U)
    io.csr_bundle.direct_write_enable := true.B
    io.ex_interrupt_assert := true.B
    io.ex_interrupt_handler_address := io.csr_bundle.mtvec
  }
  // 离开中断处理程序
  .elsewhen(io.instruction_ex === InstructionsRet.mret) {
    io.csr_bundle.mstatus_write_data := mstatus_recover_interrupt
    io.csr_bundle.mepc_write_data := io.csr_bundle.mepc
    io.csr_bundle.mcause_write_data := io.csr_bundle.mcause
    io.csr_bundle.direct_write_enable := true.B
    io.ex_interrupt_assert := true.B
    io.ex_interrupt_handler_address := io.csr_bundle.mepc
  }
  // 中断程序中或未中断
  .otherwise {
    io.csr_bundle.mstatus_write_data := io.csr_bundle.mstatus
    io.csr_bundle.mepc_write_data := io.csr_bundle.mepc
    io.csr_bundle.mcause_write_data := io.csr_bundle.mcause
    io.csr_bundle.direct_write_enable := false.B
    io.ex_interrupt_assert := false.B
    io.ex_interrupt_handler_address := 0.U
  }
}
