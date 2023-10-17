package project_foo
import chisel3._
import chisel3.util._
import common.storage._
import project_foo.examples._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage, ChiselOutputFileAnnotation}
import firrtl.options.{TargetDirAnnotation, OutputAnnotationFileAnnotation}
import firrtl.stage.OutputFileAnnotation
import firrtl.options.TargetDirAnnotation

object elaborate extends App {
	println("Generating a %s class".format(args(0)))
	val stage	= new chisel3.stage.ChiselStage
	val arr		= Array("-X", "sverilog", "--full-stacktrace")
	val dir 	= TargetDirAnnotation("Verilog")

	class TestXQueue extends Module(){
		val io = IO(new Bundle{
			val in = Flipped(Decoupled(UInt(32.W)))
			val out = (Decoupled(UInt(32.W)))
		})
		val q = XQueue(UInt(32.W),64)
		q.io.in		<> io.in
		q.io.out	<> io.out
	}
	class TestXConverter extends Module(){
		val io = IO(new Bundle{
			val out_clk = Input(Clock())
			val req = Flipped(Decoupled(UInt(32.W)))
			val res = Decoupled(UInt(32.W))
		})
		val converter = XConverter(UInt(32.W),clock,true.B,io.out_clk)
		converter.io.in <> io.req
		converter.io.out <> io.res
	}

	args(0) match{
		// case "QDMATop" => stage.execute(arr,Seq(ChiselGeneratorAnnotation(() => new QDMATop()),dir))
		case "Foo" => stage.execute(arr, Seq(ChiselGeneratorAnnotation(() => new Foo()), dir, OutputFileAnnotation(args(0)), OutputAnnotationFileAnnotation(args(0)), ChiselOutputFileAnnotation(args(0))))
		// case "Foo" => stage.execute(arr,Seq(ChiselGeneratorAnnotation(() => new Foo()),dir))
		case _ => println("Module match failed!")
	}
}