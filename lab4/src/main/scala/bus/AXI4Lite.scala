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

package bus

import chisel3._
import chisel3.experimental.ChiselEnum
import chisel3.util._
import riscv.Parameters

/**
 * 1. 看懂各变量含义
 * 2. 看懂各个接口
 * 3. 捋一遍状态机流程
 */


object AXI4Lite {
  val protWidth = 3
  val respWidth = 2
}

class AXI4LiteWriteAddressChannel(addrWidth: Int) extends Bundle {
  // 写流程的地址通道
  val AWVALID = Output(Bool())
  val AWREADY = Input(Bool())
  val AWADDR = Output(UInt(addrWidth.W))
  val AWPROT = Output(UInt(AXI4Lite.protWidth.W))

}

class AXI4LiteWriteDataChannel(dataWidth: Int) extends Bundle {
  // 写流程的数据通道
  val WVALID = Output(Bool())
  val WREADY = Input(Bool())
  val WDATA = Output(UInt(dataWidth.W))
  val WSTRB = Output(UInt((dataWidth / 8).W))
}

class AXI4LiteWriteResponseChannel extends Bundle {
  // 写流程的回复通道
  val BVALID = Input(Bool())
  val BREADY = Output(Bool())
  val BRESP = Input(UInt(AXI4Lite.respWidth.W))
}

class AXI4LiteReadAddressChannel(addrWidth: Int) extends Bundle {
  // 读流程的地址通道
  val ARVALID = Output(Bool()) // 主机向从机发送读请求
  val ARREADY = Input(Bool())  // 从机告诉主机，我已准备好读操作
  val ARADDR = Output(UInt(addrWidth.W))          // 地址
  val ARPROT = Output(UInt(AXI4Lite.protWidth.W)) //
}

class AXI4LiteReadDataChannel(dataWidth: Int) extends Bundle {
  // 读流程的数据通道
  val RVALID = Input(Bool())
  val RREADY = Output(Bool())
  val RDATA = Input(UInt(dataWidth.W))
  val RRESP = Input(UInt(AXI4Lite.respWidth.W))
}

class AXI4LiteInterface(addrWidth: Int, dataWidth: Int) extends Bundle {
    val AWVALID = Output(Bool())
    val AWREADY = Input(Bool())
    val AWADDR = Output(UInt(addrWidth.W))
    val AWPROT = Output(UInt(AXI4Lite.protWidth.W))
    val WVALID = Output(Bool())
    val WREADY = Input(Bool())
    val WDATA = Output(UInt(dataWidth.W))
    val WSTRB = Output(UInt((dataWidth / 8).W))
    val BVALID = Input(Bool())
    val BREADY = Output(Bool())
    val BRESP = Input(UInt(AXI4Lite.respWidth.W))
    val ARVALID = Output(Bool())
    val ARREADY = Input(Bool())
    val ARADDR = Output(UInt(addrWidth.W))
    val ARPROT = Output(UInt(AXI4Lite.protWidth.W))
    val RVALID = Input(Bool())
    val RREADY = Output(Bool())
    val RDATA = Input(UInt(dataWidth.W))
    val RRESP = Input(UInt(AXI4Lite.respWidth.W))
}

class AXI4LiteChannels(addrWidth: Int, dataWidth: Int) extends Bundle {
  val write_address_channel = new AXI4LiteWriteAddressChannel(addrWidth)
  val write_data_channel = new AXI4LiteWriteDataChannel(dataWidth)
  val write_response_channel = new AXI4LiteWriteResponseChannel()
  val read_address_channel = new AXI4LiteReadAddressChannel(addrWidth)
  val read_data_channel = new AXI4LiteReadDataChannel(dataWidth)
}

object AXI4LiteStates extends ChiselEnum {
  val Idle, ReadAddr, ReadData, WriteAddr, WriteData, WriteResp = Value
}

class AXI4LiteSlaveBundle(addrWidth: Int, dataWidth: Int) extends Bundle {
  // 从机与外设的端口
  val read_data = Input(UInt(dataWidth.W)) // 取得读数据
  val read_valid = Input(Bool())           // 外设可访问

  val read = Output(Bool())  // 对外设的读请求
  val write = Output(Bool()) // 对外设的写请求
  val write_data = Output(UInt(dataWidth.W)) // 输出写数据
  val write_strobe = Output(Vec(Parameters.WordSize, Bool())) // MEM控制器的把子
  val address = Output(UInt(addrWidth.W))  // 输出读写地址
}

class AXI4LiteSlave(addrWidth: Int, dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    val channels = Flipped(new AXI4LiteChannels(addrWidth, dataWidth)) // 主从接口
    val bundle = new AXI4LiteSlaveBundle(addrWidth, dataWidth) // 外设接口
    // read时，当外设read_valid时，输入addr便输出read_data
    // write时，输入addr便输出write_data
  })
  // 当前状态机状态
  val state = RegInit(AXI4LiteStates.Idle)

  // 外设接口数据寄存器。
  val addr = RegInit(0.U(dataWidth.W))// 读写地址
  val read = RegInit(false.B)         // 对外设的读请求
  val write = RegInit(false.B)        // 对外设的写请求
  val write_data = RegInit(0.U(dataWidth.W)) // 对外设的写数据
  val write_strobe = RegInit(VecInit(Seq.fill(Parameters.WordSize)(false.B))) // 把子
  // 把上个周期的外设寄存器数据交给外设。而我们不直接输出外设端口，而是先修改这些寄存器
  io.bundle.address := addr
  io.bundle.read := read
  io.bundle.write := write
  io.bundle.write_data := write_data
  write_data := io.channels.write_data_channel.WDATA // 将主机写数据交给外设
  io.bundle.write_strobe := write_strobe

  // 读流程要发射的握手信息寄存器。（握手信息只在自己的状态内置高，请脑补波形图）
  val ARREADY = RegInit(false.B)
  val RVALID = RegInit(false.B)
  val RRESP = RegInit(0.U(AXI4Lite.respWidth)) // 读操作结束信号？？什么意思
  // 每个周期初的读通道操作（保证了上升沿工作）
  // 把上个周期的握手寄存器数据交给主机。而我们不直接输出主机通道，而是先修改这些寄存器
  io.channels.read_address_channel.ARREADY := ARREADY
  io.channels.read_data_channel.RVALID := RVALID
  io.channels.read_data_channel.RRESP := RRESP

  io.channels.read_data_channel.RDATA := io.bundle.read_data // 将外设读数据输出到主机

  // 写流程要发射的握手信息寄存器。（握手信息只在自己的状态内置高，请脑补波形图）
  val AWREADY = RegInit(false.B)
  val WREADY = RegInit(false.B)
  val BVALID = RegInit(false.B)
  val BRESP = WireInit(0.U(AXI4Lite.respWidth)) // 写操作结束信号
  // 每个周期初的写通道操作（保证了上升沿工作）
  // 把上个周期的握手寄存器数据交给主机。而我们不直接输出主机通道，而是先修改这些寄存器
  io.channels.write_address_channel.AWREADY := AWREADY
  io.channels.write_data_channel.WREADY := WREADY
  io.channels.write_response_channel.BVALID := BVALID
  io.channels.write_response_channel.BRESP := BRESP


  //lab4(BUS)
  switch(state){
    is(AXI4LiteStates.Idle){ // 空闲，不通道，清空所有控制寄存器
      read := false.B
      write := false.B
      RVALID := false.B
      BVALID := false.B
      when(io.channels.read_address_channel.ARVALID){        // 来自主机的读请求
        state := AXI4LiteStates.ReadAddr
      }.elsewhen(io.channels.write_address_channel.AWVALID){ // 来自主机的读请求
        state := AXI4LiteStates.WriteAddr
      }
    }
    is(AXI4LiteStates.ReadAddr){ // 读地址
      ARREADY := true.B // 只在本状态置高
      when(io.channels.read_address_channel.ARVALID && ARREADY){ // 满足两者
        addr := io.channels.read_address_channel.ARADDR
        read := true.B // 拿到地址后，向外设发送读请求
        state := AXI4LiteStates.ReadData
        ARREADY := false.B // 只在本状态置高
      }
    }
    is(AXI4LiteStates.ReadData){ // 读数据
      RVALID := io.bundle.read_valid // 当外设可读时，读出的数据就会自动输出到CPU
      when(io.channels.read_data_channel.RREADY && RVALID) { // 满足两者
//        RRESP := true.B
        state := AXI4LiteStates.Idle
        RVALID := false.B
      }
    }
    is(AXI4LiteStates.WriteAddr){
      AWREADY := true.B
      when(AWREADY && io.channels.write_address_channel.AWVALID){
        addr := io.channels.write_address_channel.AWADDR
        state := AXI4LiteStates.WriteData
        AWREADY := false.B
      }
    }
    is(AXI4LiteStates.WriteData){
      WREADY := true.B
      when(WREADY && io.channels.write_data_channel.WVALID){
        // WD阶段就要WD
        write_strobe := io.channels.write_data_channel.WSTRB.asBools
        write_data := io.channels.write_data_channel.WDATA
        write := true.B // 向外设写请求
        state := AXI4LiteStates.WriteResp
        WREADY := false.B
      }
    }
    is(AXI4LiteStates.WriteResp){
      BVALID := true.B
      when(BVALID && io.channels.write_response_channel.BREADY){
//        BRESP := true.B
        write := false.B // 手动关闭外设写请求？？
        state := AXI4LiteStates.Idle
        BVALID := false.B
      }
    }
  }
}

class AXI4LiteMasterBundle(addrWidth: Int, dataWidth: Int) extends Bundle {
  // 主机与CPU的接口
  val read = Input(Bool())  // 来自CPU的读请求
  val write = Input(Bool()) // 来自CPU的写请求
  val write_data = Input(UInt(dataWidth.W)) // 取得读数据
  val write_strobe = Input(Vec(Parameters.WordSize, Bool())) // MEM控制器的把子
  val address = Input(UInt(addrWidth.W))   // 读写地址

  val busy = Output(Bool())         // 总线是否被占用
  val read_data = Output(UInt(dataWidth.W)) // 返回读数据
  val read_valid = Output(Bool())   // 在读操作
  val write_valid = Output(Bool())  // 在写操作
}

class AXI4LiteMaster(addrWidth: Int, dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    val channels = new AXI4LiteChannels(addrWidth, dataWidth) // 主从通道
    val bundle = new AXI4LiteMasterBundle(addrWidth, dataWidth) // 处理器接口
  })
  // 当前状态机状态
  val state = RegInit(AXI4LiteStates.Idle)

  // 处理器接口数据寄存器。把上个周期的外设数据输出到处理器
  val read_valid = RegInit(false.B)   // 告诉CPU总线读操作成功
  val write_valid = RegInit(false.B)  // 告诉CPU总线写操作成功
  val write_data = RegInit(0.U(dataWidth.W)) // 来自CPU的写数据
  val write_strobe = RegInit(VecInit(Seq.fill(Parameters.WordSize)(false.B)))//来自CPU的把子
  // 把上个周期的CPU寄存器数据交CPU。而我们不直接输出CPU端口，而是先修改这些寄存器
  io.bundle.busy := state =/= AXI4LiteStates.Idle  // 告诉处理器，总线已被占用
  io.bundle.read_valid := read_valid     // 正在读操作
  io.bundle.write_valid := write_valid   // 正在写操作

  val addr = RegInit(0.U(dataWidth.W))
  val read_data = RegInit(0.U(dataWidth.W))

  io.bundle.read_data := read_data // 将从机读数据输出到CPU

  // 读流程要发射的握手信息寄存器。（握手信息只在自己的状态内置高，请脑补波形图）
  val ARVALID = RegInit(false.B)
  val RREADY = RegInit(false.B)
  // 每个周期初的读通道操作（保证了上升沿工作）
  // 把上个周期的握手寄存器数据交给从机。而我们不直接输出从机通道，而是先修改这些寄存器
  io.channels.read_address_channel.ARVALID := ARVALID
  io.channels.read_data_channel.RREADY := RREADY
  io.channels.read_address_channel.ARADDR := 0.U  // 清空地址
  io.channels.read_address_channel.ARPROT := 0.U  // 清空？？


  // 写流程要发射的握手信息寄存器。（握手信息只在自己的状态内置高，请脑补波形图）
  val AWVALID = RegInit(false.B)
  val WVALID = RegInit(false.B)
  val BREADY = RegInit(false.B)
  // 每个周期初的写通道操作（保证了上升沿工作）
  // 把上个周期的握手寄存器数据交给从机。而我们不直接输出从机通道，而是先修改这些寄存器
  io.channels.write_address_channel.AWVALID := AWVALID
  io.channels.write_response_channel.BREADY := BREADY
  io.channels.write_data_channel.WVALID := WVALID
  io.channels.write_data_channel.WDATA := write_data       // 将CPU的写数据输出到从机

  io.channels.write_address_channel.AWADDR := 0.U
  io.channels.write_address_channel.AWPROT := 0.U
  io.channels.write_data_channel.WSTRB := write_strobe.asUInt

  //lab4(BUS)
  switch(state) {
    is(AXI4LiteStates.Idle) { // 空闲状态，不通道，清空所有控制寄存器
      WVALID := false.B
      AWVALID := false.B
      ARVALID := false.B
      RREADY := false.B
      read_valid := false.B
      write_valid := false.B
      when(io.bundle.read) {        // 来自CPU的读请求
        // 当确定是读操作时，便记录地址
        addr := io.bundle.address
        state := AXI4LiteStates.ReadAddr
      }.elsewhen(io.bundle.write) { // 来自CPU的写请求
        // 当确定是写操作时，便记录地址、写数据、把子
        addr := io.bundle.address
        write_data := io.bundle.write_data
        write_strobe := io.bundle.write_strobe
        state := AXI4LiteStates.WriteAddr
      }
    }
    is(AXI4LiteStates.ReadAddr) { // 读地址
      ARVALID := true.B // 只在本状态置高
      // 看看少写一个行不行。似乎可以
//      io.channels.read_address_channel.ARADDR := addr // 发送读地址
      when(io.channels.read_address_channel.ARREADY && ARVALID){ // 满足两者
        io.channels.read_address_channel.ARADDR := addr // 发送读地址
        state := AXI4LiteStates.ReadData
        ARVALID := false.B // 只在本状态置高
      }
    }
    is(AXI4LiteStates.ReadData) { // 读数据
      // 读操作的回复信号是和RVALID一起用的
      when(io.channels.read_data_channel.RVALID && io.channels.read_data_channel.RRESP === 0.U){
        state := AXI4LiteStates.Idle
        RREADY := true.B        // ？？为什么这里的RRESP是这样用的
        read_valid := true.B        // 告诉CPU总线读操作成功
        read_data := io.channels.read_data_channel.RDATA // 将从机数据输出到CPU
      }
    }
    is(AXI4LiteStates.WriteAddr) {
      AWVALID := true.B
      io.channels.write_address_channel.AWADDR := addr
      when(AWVALID && io.channels.write_address_channel.AWREADY){
        io.channels.write_address_channel.AWADDR := addr
        state := AXI4LiteStates.WriteData
        AWVALID := false.B
      }
    }
    is(AXI4LiteStates.WriteData) {
      WVALID := true.B
      when(WVALID && io.channels.write_data_channel.WREADY){
        // WD阶段就要WD
        state := AXI4LiteStates.WriteResp
        WVALID := false.B
      }
    }
    is(AXI4LiteStates.WriteResp) {
      BREADY := true.B
      when(BREADY && io.channels.write_response_channel.BVALID){
        state := AXI4LiteStates.Idle
        write_valid := true.B // 告诉CPU总线写操作成功
        BREADY := false.B
      }
    }
  }
}
