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

package riscv.core

import Chisel.Cat
import chisel3._
import chisel3.util.MuxLookup
import riscv.Parameters

object InterruptStatus {
  val None = 0x0.U(8.W)
  val Timer0 = 0x1.U(8.W)
  val Ret = 0xFF.U(8.W)
} // 外设在这里没写

object InterruptEntry {
  val Timer0 = 0x4.U(8.W)
}

object InterruptState {
  val Idle = 0x0.U
  val SyncAssert = 0x1.U
  val AsyncAssert = 0x2.U
  val MRET = 0x3.U
}

object CSRState {
  val Idle = 0x0.U
  val Traping = 0x1.U
  val Mret = 0x2.U
}

class CSRDirectAccessBundle extends Bundle {
  val mstatus = Input(UInt(Parameters.DataWidth))
  val mepc = Input(UInt(Parameters.DataWidth))
  val mcause = Input(UInt(Parameters.DataWidth))
  val mtvec = Input(UInt(Parameters.DataWidth))

  val mstatus_write_data= Output(UInt(Parameters.DataWidth))
  val mepc_write_data= Output(UInt(Parameters.DataWidth))
  val mcause_write_data= Output(UInt(Parameters.DataWidth))

  val direct_write_enable = Output(Bool())
}

// Core Local Interrupt Controller
class CLINT extends Module {
  val io = IO(new Bundle {
    // Interrupt signals from peripherals
    val interrupt_flag = Input(UInt(Parameters.InterruptFlagWidth))
    // 0位来自Timer，1位来自UART的外设

    val instruction = Input(UInt(Parameters.InstructionWidth))
    val instruction_address = Input(UInt(Parameters.AddrWidth))

    val jump_flag = Input(Bool())
    val jump_address = Input(UInt(Parameters.AddrWidth))

    val interrupt_handler_address = Output(UInt(Parameters.AddrWidth)) // 什么来的
    val interrupt_assert = Output(Bool())  // 什么来的

    val csr_bundle = new CSRDirectAccessBundle
  })
  val interrupt_enable = io.csr_bundle.mstatus(3)
  val instruction_address = Mux(
    io.jump_flag,
    io.jump_address,
    io.instruction_address + 4.U,
  )
  //lab2(CLINTCSR)
  // 了解RV中断处理的状态切换，要在心中有中断处理流程
  val mstatus_recover_interrupt = io.csr_bundle.mstatus(31, 4) ## io.csr_bundle.mstatus(7) ## io.csr_bundle.mstatus(2, 0)
  val mstatus_disable_interrupt = io.csr_bundle.mstatus(31, 4) ## 0.U(1.W) ## io.csr_bundle.mstatus(2, 0)
    // 三个状态：进入中断时、中断要返回了、中断中或正常中(什么都不做)
  // 进入中断
  when(io.interrupt_flag =/= InterruptStatus.None && interrupt_enable) {  // interrupt_enable来自mstatus
    io.csr_bundle.mstatus_write_data := mstatus_disable_interrupt         // 关中断（mstatus属于状态寄存器，只需要改变其中几位，比如上面的interrupt_enable）
    io.csr_bundle.mepc_write_data := instruction_address                  // 保存下一条正常指令
    io.csr_bundle.mcause_write_data := Mux(io.interrupt_flag(0), 0x80000007L.U, 0x8000000BL.U) // 0为外设中断，1为Timer中断，详细在下面
    //0x80000007L->1000 0000 0000 0000 0000 0000 0000 0111 = 1+7 = Machine timer interrupt (计时器中断)
    //0x8000000BL->1000 0000 0000 0000 0000 0000 0000 1011 = 1+11 = Machine external interrupt (外部中断)
    io.csr_bundle.direct_write_enable := true.B                   // csr写使能打开
    io.interrupt_assert := true.B                                 // Mux控制信号打开，使程序跳转到中断处理程序
    io.interrupt_handler_address := io.csr_bundle.mtvec           // 跳转目标地址为中断处理程序
                                                                   // 在这个简单的lab中，mtvec之间就是中断处理程序的入口地址了，不用进一步加工
  } // 中断结束，跳转回正常程序
  .elsewhen(io.instruction === InstructionsRet.mret) {
    io.csr_bundle.mstatus_write_data := mstatus_recover_interrupt // 开中断（mstatus属于状态寄存器，只需要改变其中几位，比如上面的interrupt_enable）
    io.csr_bundle.mepc_write_data := io.csr_bundle.mepc           // 不变
    io.csr_bundle.mcause_write_data := io.csr_bundle.mcause       // 正常程序中mcause根本用不上，所以不用管他,所以保持原样
    io.csr_bundle.direct_write_enable := true.B                   // csr写使能打开
    io.interrupt_assert := true.B                                 // Mux控制信号打开，使程序跳转回正常程序
    io.interrupt_handler_address := io.csr_bundle.mepc            // 跳转目标地址为正常指令
  } // 中断中或正常中(什么都不做)
  .otherwise {
    io.csr_bundle.mstatus_write_data := io.csr_bundle.mstatus     // 不变
    io.csr_bundle.mepc_write_data := io.csr_bundle.mepc           // 不变
    io.csr_bundle.mcause_write_data := io.csr_bundle.mcause       // 不变
    io.csr_bundle.direct_write_enable := false.B                  // csr写使能关闭
    io.interrupt_assert := false.B                                // Mux控制信号关闭，因为没有跳转的必要
                                                                  // （Mux控制信号指示取指来源：0正常下一条指令地址，1 clint给出的中断指令地址
    io.interrupt_handler_address := 0.U                           // 不跳转，不需要跳转地址，所以随便
  }

}
