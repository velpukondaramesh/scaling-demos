/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.spring.batch.partitiondemo.configuration;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import io.spring.batch.partitiondemo.domain.Transaction;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.support.MultiResourcePartitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.task.batch.partition.DeployerPartitionHandler;
import org.springframework.cloud.task.batch.partition.DeployerStepExecutionHandler;
import org.springframework.cloud.task.batch.partition.PassThroughCommandLineArgsProvider;
import org.springframework.cloud.task.batch.partition.SimpleEnvironmentVariablesProvider;
import org.springframework.cloud.task.repository.TaskRepository;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * @author Michael Minella
 */
@Configuration
public class BatchConfiguration {

	@Autowired
	private ConfigurableApplicationContext context;

	@Bean
	@Profile("manager")
	public DeployerPartitionHandler partitionHandler(TaskLauncher taskLauncher,
			JobExplorer jobExplorer,
			ApplicationContext context,
			Environment environment,
			TaskRepository taskRepository) {
		Resource resource = context.getResource("file:///Users/mminella/Documents/IntelliJWorkspace/scaling-demos/partitioned-demo/target/partitioned-demo-0.0.1-SNAPSHOT.jar");

		DeployerPartitionHandler partitionHandler = new DeployerPartitionHandler(taskLauncher, jobExplorer, resource, "step1", taskRepository);

		List<String> commandLineArgs = new ArrayList<>(3);
		commandLineArgs.add("--spring.profiles.active=worker");
		commandLineArgs.add("--spring.cloud.task.initialize.enable=false");
		commandLineArgs.add("--spring.batch.initializer.enabled=false");
		commandLineArgs.add("--spring.datasource.initialize=false");
		partitionHandler.setCommandLineArgsProvider(new PassThroughCommandLineArgsProvider(commandLineArgs));
		partitionHandler.setEnvironmentVariablesProvider(new SimpleEnvironmentVariablesProvider(environment));
		partitionHandler.setMaxWorkers(3);
		partitionHandler.setApplicationName("PartitionedBatchJobTask");

		return partitionHandler;
	}

//	@Bean
//	public TaskExecutorPartitionHandler partitionHandler() {
//		TaskExecutorPartitionHandler partitionHandler = new TaskExecutorPartitionHandler();
//
//		partitionHandler.setStep(step1());
//		partitionHandler.setTaskExecutor(new SimpleAsyncTaskExecutor());
//
//		return partitionHandler;
//	}

	@Bean
	@Profile("worker")
	public DeployerStepExecutionHandler stepExecutionHandler(JobExplorer jobExplorer, JobRepository jobRepository) {
		return new DeployerStepExecutionHandler(this.context, jobExplorer, jobRepository);
	}

	@Bean
	@StepScope
	public MultiResourcePartitioner partitioner(@Value("#{jobParameters['inputFiles']}") Resource[] resources) {
		MultiResourcePartitioner partitioner = new MultiResourcePartitioner();

		partitioner.setKeyName("file");
		partitioner.setResources(resources);

		return partitioner;
	}

	@Bean
	@StepScope
	public FlatFileItemReader<Transaction> fileTransactionReader(
			@Value("#{stepExecutionContext['file']}") Resource resource) {

		return new FlatFileItemReaderBuilder<Transaction>()
				.name("flatFileTransactionReader")
				.resource(resource)
				.delimited()
				.names(new String[] {"account", "amount", "timestamp"})
				.fieldSetMapper(fieldSet -> {
					Transaction transaction = new Transaction();

					transaction.setAccount(fieldSet.readString("account"));
					transaction.setAmount(fieldSet.readBigDecimal("amount"));
					transaction.setTimestamp(fieldSet.readDate("timestamp", "yyyy-MM-dd HH:mm:ss"));

					return transaction;
				})
				.build();
	}

	@Bean
	@StepScope
	public JdbcBatchItemWriter<Transaction> writer(DataSource dataSource) {
		return new JdbcBatchItemWriterBuilder<Transaction>()
				.dataSource(dataSource)
				.beanMapped()
				.sql("INSERT INTO TRANSACTION (ACCOUNT, AMOUNT, TIMESTAMP) VALUES (:account, :amount, :timestamp)")
				.build();
	}

	@Bean
	@Profile("manager")
	public Step partitionedMaster(JobRepository jobRepository) {
		return new StepBuilder("step1", jobRepository)
				.partitioner(step1(null, null).getName(), partitioner(null))
//				.step(step1())
				.partitionHandler(partitionHandler(null, null, null, null, null))
				.build();
	}

	@Bean
	public Step step1(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
		return new StepBuilder("step1", jobRepository)
				.<Transaction, Transaction>chunk(100, transactionManager)
				.reader(fileTransactionReader(null))
				.writer(writer(null))
				.build();
	}

//	@Bean
//	@StepScope
//	public MultiResourceItemReader<Transaction> multiResourceItemReader(
//			@Value("#{jobParameters['inputFiles']}") Resource[] resources) {
//
//		return new MultiResourceItemReaderBuilder<Transaction>()
//				.delegate(delegate())
//				.name("multiresourceReader")
//				.resources(resources)
//				.build();
//	}
//
//	@Bean
//	public FlatFileItemReader<Transaction> delegate() {
//		return new FlatFileItemReaderBuilder<Transaction>()
//				.name("flatFileTransactionReader")
//				.delimited()
//				.names(new String[] {"account", "amount", "timestamp"})
//				.fieldSetMapper(fieldSet -> {
//					Transaction transaction = new Transaction();
//
//					transaction.setAccount(fieldSet.readString("account"));
//					transaction.setAmount(fieldSet.readBigDecimal("amount"));
//					transaction.setTimestamp(fieldSet.readDate("timestamp", "yyyy-MM-dd HH:mm:ss"));
//
//					return transaction;
//				})
//				.build();
//	}

	@Bean
	@Profile("!worker")
	public Job partitionedJob(JobRepository jobRepository) {
		return new JobBuilder("partionedJob", jobRepository)
				.start(partitionedMaster(null))
				.build();
	}
}
