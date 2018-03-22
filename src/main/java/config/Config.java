package config;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
@Lazy
public class Config {

	@Bean(name = "myfactorybean")
	@Lazy(value=true)
	public FactoryBean<Object> getMyFactoryBean() {
		return new MyFactoryBean();
	}
	
	public static class MyFactoryBean implements FactoryBean<Object> {

		@Override
		public Object getObject() throws Exception {
			return null;
		}

		@Override
		public Class getObjectType() {
			return null;
		}
		
	}

}
