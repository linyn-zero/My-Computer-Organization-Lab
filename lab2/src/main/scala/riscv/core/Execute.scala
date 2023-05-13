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
import chisel3.util.{Cat, MuxLookup}
import riscv.Parameters

class Execute extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(Parameters.InstructionWidth))
    val instruction_address = Input(UInt(Parameters.AddrWidth))
    val reg1_data = Input(UInt(Parameters.DataWidth))
    val reg2_data = Input(UInt(Parameters.DataWidth))
    val immediate = Input(UInt(Parameters.DataWidth))
    val aluop1_source = Input(UInt(1.W))
    val aluop2_source = Input(UInt(1.W))
    val csr_reg_read_data = Input(UInt(Parameters.DataWidth))

    val mem_alu_result = Output(UInt(Parameters.DataWidth))
    val csr_reg_write_data = Output(UInt(Parameters.DataWidth))
    val if_jump_flag = Output(Bool())
    val if_jump_address = Output(UInt(Parameters.DataWidth))
  })

  val opcode = io.instruction(6, 0)
  val funct3 = io.instruction(14, 12)
  val funct7 = io.instruction(31, 25)

  val alu = Module(new ALU)
  val alu_ctrl = Module(new ALUControl)

  alu_ctrl.io.opcode := opcode
  alu_ctrl.io.funct3 := funct3
  alu_ctrl.io.funct7 := funct7
  alu.io.func := alu_ctrl.io.alu_funct
  alu.io.op1 := Mux(
    io.aluop1_source === ALUOp1Source.InstructionAddress,
    io.instruction_address,
    io.reg1_data,
  )
  alu.io.op2 := Mux(
    io.aluop2_source === ALUOp2Source.Immediate,
    io.immediate,
    io.reg2_data,
  )
  io.if_jump_flag := opcode === Instructions.jal ||
    (opcode === Instructions.jalr) ||
    (opcode === InstructionTypes.B) && MuxLookup(
      funct3,
      false.B,
      IndexedSeq(
        InstructionsTypeB.beq -> (io.reg1_data === io.reg2_data),
        InstructionsTypeB.bne -> (io.reg1_data =/= io.reg2_data),
        InstructionsTypeB.blt -> (io.reg1_data.asSInt < io.reg2_data.asSInt),
        InstructionsTypeB.bge -> (io.reg1_data.asSInt >= io.reg2_data.asSInt),
        InstructionsTypeB.bltu -> (io.reg1_data.asUInt < io.reg2_data.asUInt),
        InstructionsTypeB.bgeu -> (io.reg1_data.asUInt >= io.reg2_data.asUInt)
      )
    )
  io.if_jump_address := io.immediate + Mux(opcode === Instructions.jalr, io.reg1_data, io.instruction_address)
  io.mem_alu_result := alu.io.result
  // lab2(CLINTCSR)
  // 需要先了解RV的中断指令
  // 目标是在处理 CSR 指令时能够正确地得到写入 CSR 寄存器的数据
  val uimm = Cat(0.U(27.W),io.instruction(19,15))

  io.csr_reg_write_data := MuxLookup(
    funct3,
    0.U,
    IndexedSeq(
      InstructionsTypeCSR.csrrw -> io.reg1_data,
      InstructionsTypeCSR.csrrs -> io.csr_reg_read_data.|(io.reg1_data.asUInt),
      InstructionsTypeCSR.csrrc -> io.csr_reg_read_data.&((~io.reg1_data).asUInt),
      InstructionsTypeCSR.csrrwi -> uimm,
      InstructionsTypeCSR.csrrsi -> io.csr_reg_read_data.|(uimm.asUInt),
      InstructionsTypeCSR.csrrci -> io.csr_reg_read_data.&((~uimm).asUInt)
    )
  )

}
