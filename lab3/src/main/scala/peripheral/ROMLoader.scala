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
import riscv.Parameters

class ROMLoader(capacity: Int) extends Module {
  val io = IO(new Bundle {
    val bundle = Flipped(new RAMBundle)

    val rom_address = Output(UInt(Parameters.AddrWidth))
    val rom_data = Input(UInt(Parameters.InstructionWidth))

    val load_address = Input(UInt(Parameters.AddrWidth))
    val load_finished = Output(Bool())
  })

  val address = RegInit(0.U(32.W))
  val valid = RegInit(false.B)

  io.bundle.write_strobe := VecInit(Seq.fill(Parameters.WordSize)(false.B))
  io.bundle.address := 0.U
  io.bundle.write_data := 0.U
  io.bundle.write_enable := false.B
  when(address <= (capacity - 1).U) {
    io.bundle.write_enable := true.B
    io.bundle.write_data := io.rom_data
    io.bundle.address := (address << 2.U).asUInt + io.load_address
    io.bundle.write_strobe := VecInit(Seq.fill(Parameters.WordSize)(true.B))
    address := address + 1.U
    when(address === (capacity - 1).U) {
      valid := true.B
    }
  }
  io.load_finished := valid
  io.rom_address := address
}
