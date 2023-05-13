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

package riscv.core.threestage

import chisel3._
import chisel3.util.MuxCase
import riscv.Parameters

object StallStates {
  val None = 0.U
  val PC = 1.U
  val IF = 2.U
  val ID = 3.U
}
class Control extends Module {
  val io = IO(new Bundle {
    val jump_flag = Input(Bool())

    val if2id_flush = Output(Bool())
    val id2ex_flush = Output(Bool())
  })
  // Lab3(Flush)
  io.if2id_flush := io.jump_flag
  io.id2ex_flush := io.jump_flag
}
