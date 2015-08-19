/**
 * (C) Copyright IBM Corp. 2010, 2015
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.ibm.bi.dml.runtime.instructions.spark;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.Function;

import scala.Tuple2;

import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.DMLUnsupportedOperationException;
import com.ibm.bi.dml.runtime.controlprogram.context.ExecutionContext;
import com.ibm.bi.dml.runtime.controlprogram.context.SparkExecutionContext;
import com.ibm.bi.dml.runtime.functionobjects.Multiply;
import com.ibm.bi.dml.runtime.functionobjects.Plus;
import com.ibm.bi.dml.runtime.functionobjects.SwapIndex;
import com.ibm.bi.dml.runtime.instructions.InstructionUtils;
import com.ibm.bi.dml.runtime.instructions.cp.CPOperand;
import com.ibm.bi.dml.runtime.instructions.spark.utils.RDDAggregateUtils;
import com.ibm.bi.dml.runtime.matrix.data.MatrixBlock;
import com.ibm.bi.dml.runtime.matrix.data.MatrixIndexes;
import com.ibm.bi.dml.runtime.matrix.operators.AggregateBinaryOperator;
import com.ibm.bi.dml.runtime.matrix.operators.AggregateOperator;
import com.ibm.bi.dml.runtime.matrix.operators.Operator;
import com.ibm.bi.dml.runtime.matrix.operators.ReorgOperator;


public class ZipmmSPInstruction extends BinarySPInstruction 
{
	public ZipmmSPInstruction(Operator op, CPOperand in1, CPOperand in2, CPOperand out, String opcode, String istr )
	{
		super(op, in1, in2, out, opcode, istr);
		_sptype = SPINSTRUCTION_TYPE.ZIPMM;
	}

	/**
	 * 
	 * @param str
	 * @return
	 * @throws DMLRuntimeException
	 */
	public static ZipmmSPInstruction parseInstruction( String str ) 
		throws DMLRuntimeException 
	{
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		String opcode = parts[0];

		if ( opcode.equalsIgnoreCase("zipmm")) {
			CPOperand in1 = new CPOperand(parts[1]);
			CPOperand in2 = new CPOperand(parts[2]);
			CPOperand out = new CPOperand(parts[3]);
			AggregateOperator agg = new AggregateOperator(0, Plus.getPlusFnObject());
			AggregateBinaryOperator aggbin = new AggregateBinaryOperator(Multiply.getMultiplyFnObject(), agg);
			
			return new ZipmmSPInstruction(aggbin, in1, in2, out, opcode, str);
		} 
		else {
			throw new DMLRuntimeException("ZipmmSPInstruction.parseInstruction():: Unknown opcode " + opcode);
		}
		
	}
	
	@Override
	public void processInstruction(ExecutionContext ec) 
		throws DMLRuntimeException, DMLUnsupportedOperationException
	{	
		SparkExecutionContext sec = (SparkExecutionContext)ec;
		
		//get rdd inputs (for computing r = t(X)%*%y via r = t(t(y)%*%X))
		JavaPairRDD<MatrixIndexes,MatrixBlock> in1 = sec.getBinaryBlockRDDHandleForVariable( input1.getName() ); //X
		JavaPairRDD<MatrixIndexes,MatrixBlock> in2 = sec.getBinaryBlockRDDHandleForVariable( input2.getName() ); //y
		
		//process core zipmm matrix multiply (in contrast to cpmm, the join over original indexes
		//preserves the original partitioning and with that potentially unnecessary join shuffle)
		JavaPairRDD<MatrixIndexes,MatrixBlock> out = in1
				   .join(in2)                              // join over original indexes
				   .mapValues(new ZipMultiplyFunction());  // compute block multiplications, incl t(y)
				   
		//single-block aggregation (guaranteed by zipmm blocksize constraint)
		MatrixBlock out2 = RDDAggregateUtils.sumStable(out);
		
		//final transpose of result (for t(t(y)%*%X)))
		ReorgOperator rop = new ReorgOperator(SwapIndex.getSwapIndexFnObject());
		out2 = (MatrixBlock)out2.reorgOperations(rop, new MatrixBlock(), 0, 0, 0);
		
		//put output block into symbol table (no lineage because single block)
		//this also includes implicit maintenance of matrix characteristics
		sec.setMatrixOutput(output.getName(), out2);	
	}

	/**
	 * 
	 *
	 */
	private static class ZipMultiplyFunction implements Function<Tuple2<MatrixBlock,MatrixBlock>, MatrixBlock> 
	{
		private static final long serialVersionUID = -6669267794926220287L;
		
		private AggregateBinaryOperator _abop = null;
		private ReorgOperator _rop = null;
		
		public ZipMultiplyFunction()
		{
			AggregateOperator agg = new AggregateOperator(0, Plus.getPlusFnObject());
			_abop = new AggregateBinaryOperator(Multiply.getMultiplyFnObject(), agg);
			_rop = new ReorgOperator(SwapIndex.getSwapIndexFnObject());
		}

		@Override
		public MatrixBlock call(Tuple2<MatrixBlock, MatrixBlock> arg0)
			throws Exception 
		{
			MatrixBlock in1 = arg0._1();
			MatrixBlock in2 = arg0._2();
			
			//transpose right input (for vectors no-op)
			MatrixBlock tmp = (MatrixBlock)in2.reorgOperations(_rop, new MatrixBlock(), 0, 0, 0);
			
			//core matrix multiplication (for t(y)%*%X)
			return (MatrixBlock)tmp.aggregateBinaryOperations(tmp, in1, new MatrixBlock(), _abop);
		}
	}
}
