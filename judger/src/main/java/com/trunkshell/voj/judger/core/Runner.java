package com.trunkshell.voj.judger.core;

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.trunkshell.voj.judger.model.Language;
import com.trunkshell.voj.judger.model.Submission;
import com.trunkshell.voj.judger.util.NativeLibraryLoader;

/**
 * 本地程序执行器, 用于执行本地应用程序. 包括编译器(gcc)以及用户提交的代码所编译出的程序.
 * 
 * @author Xie Haozhe
 */
@Component
public class Runner {
	/**
	 * 获取(用户)程序运行结果.
	 * 
	 * @param submission - 评测记录对象
	 * @param checkpointId - 当前测试点编号
	 * @param workDirectory - 编译生成结果的目录以及程序输出的目录
	 * @param baseFileName - 待执行的应用程序文件名(不包含文件后缀)
	 * @param inputFilePath - 输入文件路径
	 * @param outputFilePath - 输出文件路径
	 * @return 一个包含程序运行结果的Map<String, Object>对象
	 */
	public Map<String, Object> getRuntimeResult(Submission submission, int checkpointId, 
			String workDirectory, String baseFileName, String inputFilePath, String outputFilePath) {
		String commandLine = getCommandLine(submission, workDirectory, baseFileName);
		int timeLimit = getTimeLimit(submission);
		int memoryLimit = getMemoryLimit(submission);
		
		Map<String, Object> result = new HashMap<String, Object>(4, 1);
		String runtimeResultSlug = "SE";
		int timeUsed = 0;
		int memoryUsed = 0;
		
		try {
			logger.info(String.format("[Submission #%d] Start running with command %s. (TimeLimit=%d, MemoryLimit=%s)", 
								new Object[] { submission.getSubmissionId(), commandLine, timeLimit, memoryLimit }));
			Map<String, Object> runtimeResult = getRuntimeResult(commandLine, 
					systemUsername, systemPassword, inputFilePath, outputFilePath, 
					timeLimit, memoryLimit);
			
			int exitCode = (Integer) runtimeResult.get("exitCode");
			timeUsed = (Integer) runtimeResult.get("timeUsage");
			memoryUsed = (Integer) runtimeResult.get("memoryUsage");
			runtimeResultSlug = getRuntimeResultSlug(exitCode, timeLimit, timeUsed, memoryLimit, memoryUsed);
		} catch ( Exception ex ) {
			ex.printStackTrace();
			logger.catching(ex);
		}
		
		result.put("runtimeResult", runtimeResultSlug);
		result.put("timeUsed", timeUsed);
		result.put("memoryUsed", memoryUsed);
		return result;
	}
	
	/**
	 * 获取待执行的命令行.
	 * @param submission - 评测记录对象
	 * @param workDirectory - 编译生成结果的目录以及程序输出的目录
	 * @param baseFileName - 待执行的应用程序文件名(不包含文件后缀)
	 * @return 待执行的命令行
	 */
	private String getCommandLine(Submission submission, 
			String workDirectory, String baseFileName) {
		Language language = submission.getLanguage();
		String filePathWithoutExtension = String.format("%s/%s", 
											new Object[] {workDirectory, baseFileName});
		StringBuffer runCommand = new StringBuffer(language.getRunCommand()
													.replaceAll("\\{filename\\}", filePathWithoutExtension)); 
		
		if ( language.getLanguageName().equalsIgnoreCase("Java") ) {
			int lastIndexOfSpace = runCommand.lastIndexOf("/");
			runCommand.setCharAt(lastIndexOfSpace, ' ');
		}
		return runCommand.toString();
	}
	
	/**
	 * 获取当前测试点输出路径.
	 * @param workDirectory - 编译生成结果的目录以及程序输出的目录
	 * @param checkpointId - 当前测试点编号
	 * @return 当前测试点输出路径
	 */
	private String getOutputFilePath(String workDirectory, int checkpointId) {
		return String.format("%s/output#%s", 
				new Object[] {workDirectory, checkpointId});
	}
	
	/**
	 * 根据不同语言获取最大时间限制.
	 * @param submission - 评测记录对象
	 * @return 最大时间限制
	 */
	private int getTimeLimit(Submission submission) {
		Language language = submission.getLanguage();
		int timeLimit = submission.getProblem().getTimeLimit();
		
		if ( language.getLanguageName().equalsIgnoreCase("Java") ) {
			timeLimit *= 2;
		}
		return timeLimit;
	}
	
	/**
	 * 根据不同语言获取最大空间限制.
	 * @param submission - 评测记录对象
	 * @return 最大空间限制
	 */
	private int getMemoryLimit(Submission submission) {
		int memoryLimit = submission.getProblem().getMemoryLimit();
		return memoryLimit;
	}

	/**
	 * 根据JNI返回的结果封装评测结果.
	 * @param exitCode - 程序退出状态位
	 * @param timeLimit - 最大时间限制
	 * @param timeUsed - 程序运行所用时间
	 * @param memoryLimit - 最大空间限制
	 * @param memoryUsed - 程序运行所用空间(最大值)
	 * @return 程序运行结果的唯一英文缩写
	 */
	private String getRuntimeResultSlug(int exitCode, int timeLimit, int timeUsed, int memoryLimit, int memoryUsed) {
		if ( exitCode == 0 ) {
			// Output will be compared in next stage
			return "AC";
		}
		if ( timeUsed > timeLimit ) {
			return "TLE";
		}
		if ( memoryUsed > memoryLimit ) {
			return "MLE";
		}
		return "RE";
	}
	
	/**
	 * 获取(编译)程序运行结果.
	 * 
	 * @param commandLine - 待执行程序的命令行
	 * @param inputFilePath - 输入文件路径(可为NULL)
	 * @param outputFilePath - 输出文件路径(可为NULL)
	 * @param timeLimit - 时间限制(单位ms, 0表示不限制)
	 * @param memoryLimit - 内存限制(单位KB, 0表示不限制)
	 * @return 一个包含程序运行结果的Map<String, Object>对象
	 */
	public Map<String, Object> getRuntimeResult(String commandLine,
			String inputFilePath, String outputFilePath, int timeLimit,
			int memoryLimit) {
		Map<String, Object> result = null;
		try {
			result = getRuntimeResult(commandLine, systemUsername, systemPassword,
						inputFilePath, outputFilePath, timeLimit, memoryLimit);
		} catch ( Exception ex ) {
			logger.catching(ex);
		}
		return result;
	}

	/**
	 * 获取程序运行结果.
	 * 
	 * @param commandLine - 待执行程序的命令行
	 * @param systemUsername - 登录操作系统的用户名
	 * @param systemPassword - 登录操作系统的密码
	 * @param inputFilePath - 输入文件路径(可为NULL)
	 * @param outputFilePath - 输出文件路径(可为NULL)
	 * @param timeLimit - 时间限制(单位ms, 0表示不限制)
	 * @param memoryLimit - 内存限制(单位KB, 0表示不限制)
	 * @return 一个包含程序运行结果的Map<String, Object>对象
	 */
	public native Map<String, Object> getRuntimeResult(String commandLine,
			String systemUsername, String systemPassword, String inputFilePath,
			String outputFilePath, int timeLimit, int memoryLimit);

	/**
	 * 登录操作系统的用户名. 
	 * 为了安全, 我们建议评测程序以低权限的用户运行.
	 */
	@Value("${system.username}")
	private String systemUsername;

	/**
	 * 登录操作系统的密码. 
	 * 为了安全, 我们建议评测程序以低权限的用户运行.
	 */
	@Value("${system.password}")
	private String systemPassword;

	/**
	 * 日志记录器.
	 */
	private static final Logger logger = LogManager.getLogger(Runner.class);

	/**
	 * Load Native Library.
	 */
	static {
		try {
			NativeLibraryLoader.loadLibrary("JudgerCore");
		} catch (Exception ex) {
			ex.printStackTrace();
			logger.catching(ex);
		}
	}
}
