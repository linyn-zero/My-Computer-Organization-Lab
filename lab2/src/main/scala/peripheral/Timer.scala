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

object RegAddr{
  val enable = 0x00000008.U
  val limit = 0x00000004.U
}
class Timer extends Module {
  val io = IO(new Bundle {
    val bundle = new RAMBundle
    val signal_interrupt = Output(Bool())

    val debug_limit = Output(UInt(Parameters.DataWidth))
    val debug_enabled = Output(Bool())
  })

  val count = RegInit(0.U(32.W))
  val limit = RegInit(10000000.U(32.W))
  io.debug_limit := limit
  val enabled = RegInit(true.B)
  io.debug_enabled := enabled

  //lab2(CLINTCSR)
  //finish the read-write for count,limit,enabled. And produce appropriate signal_interrupt
  io.bundle.read_data := MuxLookup(
    io.bundle.address,
    0.U,
    IndexedSeq(
      RegAddr.limit -> limit,
      RegAddr.enable -> enabled.asUInt
    )
  )
  when(io.bundle.write_enable){
    // 写enabled
    when (io.bundle.address === RegAddr.enable){
      enabled := io.bundle.write_data =/= 0.U
    }
    // 写limit，此时count置0，重新计时
    .elsewhen(io.bundle.address === RegAddr.limit){
      limit := io.bundle.write_data
      count := 0.U
    }
  }
  io.signal_interrupt := enabled && (count >= limit-5.U)

  when(count >= limit){
    count := 0.U
  }.otherwise{
    count := count+1.U
  }

}
