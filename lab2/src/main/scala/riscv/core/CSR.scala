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

import chisel3._
import chisel3.util._
import riscv.Parameters


object CSRRegister {
  // Refer to Spec. Vol.II Page 8-10
  val MSTATUS = 0x300.U(Parameters.CSRRegisterAddrWidth)
  val MIE = 0x304.U(Parameters.CSRRegisterAddrWidth)
  val MTVEC = 0x305.U(Parameters.CSRRegisterAddrWidth)
  val MSCRATCH = 0x340.U(Parameters.CSRRegisterAddrWidth)
  val MEPC = 0x341.U(Parameters.CSRRegisterAddrWidth)
  val MCAUSE = 0x342.U(Parameters.CSRRegisterAddrWidth)
  val CycleL = 0xc00.U(Parameters.CSRRegisterAddrWidth)
  val CycleH = 0xc80.U(Parameters.CSRRegisterAddrWidth)
}

class CSR extends Module { // 控制与状态寄存器
  val io = IO(new Bundle {
    val reg_read_address_id = Input(UInt(Parameters.CSRRegisterAddrWidth))
    val reg_write_enable_id= Input(Bool())
    val reg_write_address_id = Input(UInt(Parameters.CSRRegisterAddrWidth))
    val reg_write_data_ex= Input(UInt(Parameters.DataWidth))
    val debug_reg_read_address = Input(UInt(Parameters.CSRRegisterAddrWidth))

    val debug_reg_read_data = Output(UInt(Parameters.DataWidth))
    val reg_read_data = Output(UInt(Parameters.DataWidth))

    val clint_access_bundle = Flipped(new CSRDirectAccessBundle)  // CLINT过来的接线
  })

  val mstatus = RegInit(UInt(Parameters.DataWidth), 0.U)
  val mie = RegInit(UInt(Parameters.DataWidth), 0.U)
  val mtvec = RegInit(UInt(Parameters.DataWidth), 0.U)
  val mscratch = RegInit(UInt(Parameters.DataWidth), 0.U)
  val mepc = RegInit(UInt(Parameters.DataWidth), 0.U)
  val mcause = RegInit(UInt(Parameters.DataWidth), 0.U)
  val cycles = RegInit(UInt(64.W), 0.U)
  val regLUT =
    IndexedSeq(
      CSRRegister.MSTATUS -> mstatus,
      CSRRegister.MIE -> mie,
      CSRRegister.MTVEC -> mtvec,
      CSRRegister.MSCRATCH -> mscratch,
      CSRRegister.MEPC -> mepc,
      CSRRegister.MCAUSE -> mcause,
      CSRRegister.CycleL -> cycles(31, 0),
      CSRRegister.CycleH -> cycles(63, 32),
    )
  cycles := cycles + 1.U

  // If the pipeline and the CLINT are going to read and write the CSR at the same time, let the pipeline write first.
  // This is implemented in a single cycle by passing reg_write_data_ex to clint and writing the data from the CLINT to the CSR.
  io.reg_read_data := MuxLookup(io.reg_read_address_id, 0.U, regLUT)
  io.debug_reg_read_data := MuxLookup(io.debug_reg_read_address, 0.U,regLUT)

  //lab2(CLINTCSR)   写CSR寄存器
  // 来自CLINT
  when(io.clint_access_bundle.direct_write_enable) {
    mstatus := io.clint_access_bundle.mstatus_write_data
    mepc := io.clint_access_bundle.mepc_write_data
    mcause := io.clint_access_bundle.mcause_write_data
  }
  // 来自CSR指令
  .elsewhen (io.reg_write_enable_id) {
    when(io.reg_write_address_id === CSRRegister.MSTATUS) {
      mstatus := io.reg_write_data_ex
    }.elsewhen (io.reg_write_address_id === CSRRegister.MEPC) {
      mepc := io.reg_write_data_ex
    }.elsewhen (io.reg_write_address_id === CSRRegister.MCAUSE) {
      mcause := io.reg_write_data_ex
    }.elsewhen (io.reg_write_address_id === CSRRegister.MIE) {
      mie := io.reg_write_data_ex
    }.elsewhen (io.reg_write_address_id === CSRRegister.MTVEC) {
      mtvec := io.reg_write_data_ex
    }.elsewhen (io.reg_write_address_id === CSRRegister.MSCRATCH) {
      mscratch := io.reg_write_data_ex
    }
  }
  // CLINT读CSR寄存器（写出的寄存器的值要和现在的寄存器的值同步，也就是先修改，再输出(电路由于是并行的，上面修改了，下面还是会读到老数据）
  io.clint_access_bundle.mstatus := Mux(
    io.reg_write_enable_id && io.reg_write_address_id === CSRRegister.MSTATUS,
    io.reg_write_data_ex,
    mstatus
  )
  io.clint_access_bundle.mtvec := Mux(
    io.reg_write_enable_id && io.reg_write_address_id === CSRRegister.MTVEC,
    io.reg_write_data_ex,
    mtvec
  )
  io.clint_access_bundle.mcause := Mux(
    io.reg_write_enable_id && io.reg_write_address_id === CSRRegister.MCAUSE,
    io.reg_write_data_ex,
    mcause
  )
  io.clint_access_bundle.mepc := Mux(
    io.reg_write_enable_id && io.reg_write_address_id === CSRRegister.MEPC,
    io.reg_write_data_ex,
    mepc
  )
}
