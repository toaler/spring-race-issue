package sandbox;

import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListenerMethodProcessor;

import config.Config.MyFactoryBean;

public class SpringCreateFactoryBeanSingletonRaceTest {
	
	// Typically DefaultListableBeanFactory.doGetBeanNamesForType is
	// triggered via ApplicationContext.refresh via
	// EventListenerMethodProcessor.getEventListnerFactories() in a
	// single thread prior to when the application context is available
	// for use. See typical stack:
	//
    //				ConstructorResolver.instantiateUsingFactoryMethod(String, RootBeanDefinition, Object[]) line: 355	
    //				DefaultListableBeanFactory(AbstractAutowireCapableBeanFactory).instantiateUsingFactoryMethod(String, RootBeanDefinition, Object[]) line: 1250	
    //				DefaultListableBeanFactory(AbstractAutowireCapableBeanFactory).createBeanInstance(String, RootBeanDefinition, Object[]) line: 1099	
    //				DefaultListableBeanFactory(AbstractAutowireCapableBeanFactory).getSingletonFactoryBeanForTypeCheck(String, RootBeanDefinition) line: 946	
    //				DefaultListableBeanFactory(AbstractAutowireCapableBeanFactory).getTypeForFactoryBean(String, RootBeanDefinition) line: 833	
    //				DefaultListableBeanFactory(AbstractBeanFactory).isTypeMatch(String, ResolvableType) line: 557	
    //				DefaultListableBeanFactory.doGetBeanNamesForType(ResolvableType, boolean, boolean) line: 428	
    //				DefaultListableBeanFactory.getBeanNamesForType(Class<?>, boolean, boolean) line: 399	
    //				DefaultListableBeanFactory.getBeanNamesForType(Class<?>) line: 385	
    //				AnnotationConfigApplicationContext(AbstractApplicationContext).getBeanNamesForType(Class<?>) line: 1182
	//
	// However in application thats setup doesn't cause
	// DefaultListableBeanFactory.doGetBeanNamesForType to run prior to
	// AC.refresh finishing, then there is thread safety issue when two
	// threads race calling DefaultListableBeanFactory.getBeanNamesForType
	//
	//				t0 T1 AbstractBeanFactory.isTypeMatch checks for bean via getSingleton which returns null
	//				t1 T2 AbstractBeanFactory.isTypeMatch checks for bean via getSingleton which returns null
	//				t2 T1 AbstractAutowireCapableBeanFactory.getSingletonFactoryBeanForTypeCheck acquires getSingletonMutex lock
	//				t3 T2 AbstractAutowireCapableBeanFactory.getSingletonFactoryBeanForTypeCheck blocks on getSingletonMutex lock
	//				t4 T1 Creates bean instance by callingCreateBeanInstance all the way down to ConstructorResovler.instantiateUsingFactoryMethod 
	//				      which calls getBean that creates the non existing instance and puts it into the registry.
	//				t5 T1 Releases getSingletonMutex lock
	//				t6 T2 Acquires lock and attempts to create factory bean instance, however when runtime gets to instantiateUsingFactoryMethod 
	//				      and the beanFactory.containsSingleton occurs to check if the factory bean has been added to the bean factory, which it
	//				      was in T4, which causes the ImplicitlyAppeardSingletonException to be thrown.
	//
	//	To prevent this from happening the proposed change is to always have AbstractAutowireCapableBeanFactory.getSingletonFactoryBeanForTypeCheck 
	//  check after it acquires the mutex if the singleton factory bean has been added in the registry, guarding it from failing with 
	//  ImplicitlyAppeardSingletonException when another thread added the factory bean to the registry sometime between t1 and t5.
	//	
	//  The proposed change is as follows:
	//
	//				diff --git a/spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java b/spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java
	//				index 41589161ab..3efa362bd6 100644
	//				--- a/spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java
	//				+++ b/spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java
	//				@@ -923,6 +923,11 @@ public abstract class AbstractAutowireCapableBeanFactory extends AbstractBeanFac
	//				        @Nullable
	//				        private FactoryBean<?> getSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
	//				                synchronized (getSingletonMutex()) {
	//				+                       Object beanInstance = getSingleton(beanName, false);
	//				+                       if (beanInstance != null) {
	//				+                           return (FactoryBean<?>) beanInstance;
	//				+                       }
	//				+
	//				                        BeanWrapper bw = this.factoryBeanInstanceCache.get(beanName);
	//				                        if (bw != null) {
	//				                                return (FactoryBean<?>) bw.getWrappedInstance();

	@Test
	public void testRaceCondition() {
		
		/** Flip the pom to 5.0.5.BUILD-SNAPSHOT which contains the fix will allow this to run */
		
		while (1 == 1) {
			try (AnnotationConfigApplicationContext acac = new AnnotationConfigApplicationContext()) {

				acac.registerBean("org.springframework.context.event.internalEventListenerProcessor",
						MyEventListenerMethodProcessor.class);

				acac.scan("config");
				acac.refresh();

				Thread t = new Thread(() -> acac.getBeanNamesForType(MyFactoryBean.class));
				t.start();
				acac.getBeanNamesForType(MyFactoryBean.class);
				t.join();
			} catch (Throwable t) {
				System.out.println(t.getMessage());
				System.out.println(t.getCause());
				t.printStackTrace();
				System.exit(1);
			}
		}
	}

	public static class MyEventListenerMethodProcessor extends EventListenerMethodProcessor {
		@Override
		public void afterSingletonsInstantiated() {
		}
	}
}
