/*******************************************************************************
 * DIANNE  - Framework for distributed artificial neural networks
 * Copyright (C) 2015  iMinds - IBCN - UGent
 *
 * This file is part of DIANNE.
 *
 * DIANNE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     Tim Verbelen, Steven Bohez
 *******************************************************************************/
package be.iminds.iot.dianne.builder;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import be.iminds.iot.dianne.api.dataset.DianneDatasets;
import be.iminds.iot.dianne.api.dataset.Sequence;
import be.iminds.iot.dianne.api.nn.Dianne;
import be.iminds.iot.dianne.api.nn.NeuralNetwork;
import be.iminds.iot.dianne.api.nn.platform.DiannePlatform;
import be.iminds.iot.dianne.api.rl.dataset.ExperiencePool;
import be.iminds.iot.dianne.api.rl.dataset.ExperiencePoolSample;
import be.iminds.iot.dianne.tensor.Tensor;
import be.iminds.iot.dianne.tensor.TensorOps;
import be.iminds.iot.dianne.tensor.util.JsonConverter;

@Component(service = { javax.servlet.Servlet.class }, 
	property = { "alias:String=/dianne/sb",
		 		 "osgi.http.whiteboard.servlet.pattern=/dianne/sb",
		 		 "osgi.http.whiteboard.servlet.asyncSupported:Boolean=true",
				 "aiolos.proxy=false" }, 
	immediate = true)
public class DianneSB extends HttpServlet {
	
	private static final long serialVersionUID = 1L;

	private JsonParser parser = new JsonParser();
	private JsonConverter converter = new JsonConverter();
	
	private Dianne dianne;
	private DiannePlatform platform;
	private DianneDatasets datasets;
	
	private Map<String, NeuralNetwork> nns = new ConcurrentHashMap<>();
	
	@Reference
	void setDianne(Dianne d){
		dianne = d;
	}
	
	@Reference
	void setDiannePlatform(DiannePlatform p){
		platform = p;
	}
	
	@Reference(cardinality=ReferenceCardinality.OPTIONAL)
	void setDianneDatasets(DianneDatasets d){
		datasets = d;
	}
	
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		
		
	}
	
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		response.setContentType("application/json");
		String d = request.getParameter("pool");
		if(d == null){
			System.out.println("No xp pool provided");
			return;
		}
		
		ExperiencePool dataset = (ExperiencePool)datasets.getDataset(d);
		if(dataset == null){
			System.out.println("No such xp pool found: "+d);
			return;
		}
		
		String enc = request.getParameter("encoder");
		if(enc == null){
			System.out.println("No encoder provided");
			return;
		}
		
		String tag = request.getParameter("tag");
		
		NeuralNetwork encoder = nns.get(enc);
		if(encoder == null){
			try {
				encoder = dianne.getNeuralNetwork(platform.deployNeuralNetwork(enc, new String[]{tag})).getValue();
				nns.put(enc, encoder);
			} catch (Exception e) {
				e.printStackTrace();
				return;
			}
		} else {
			try {
				encoder.loadParameters(tag);
			} catch (Exception e) {
			}
		}
		
		NeuralNetwork decoder = null;
		String dec = request.getParameter("decoder");
		if(dec != null){
			decoder = nns.get(dec);
			if(decoder == null){
				try {
					decoder = dianne.getNeuralNetwork(platform.deployNeuralNetwork(dec, new String[]{tag})).getValue();
					nns.put(dec, decoder);
				} catch (Exception e) {
					e.printStackTrace();
				}
			} else {
				try {
					decoder.loadParameters(tag);
				} catch (Exception e) {
				}
			}
		}

		NeuralNetwork predictor = null;
		String pre = request.getParameter("predictor");
		if(pre != null){
			predictor = nns.get(pre);
			if(predictor == null){
				try {
					predictor = dianne.getNeuralNetwork(platform.deployNeuralNetwork(pre, new String[]{tag})).getValue();
					nns.put(pre, predictor);
				} catch (Exception e) {
					e.printStackTrace();
				}
			} else {
				try {
					predictor.loadParameters(tag);
				} catch (Exception e) {
				}
			}
		} 

		
		String seq = request.getParameter("sequence");
		if(seq == null){
			System.out.println("No sequence provided");
			return;
		}
		int sequence = Integer.parseInt(seq);
		
		String in = request.getParameter("index");
		if(in == null){
			System.out.println("No index provided");
			return;
		}
		int index = Integer.parseInt(in);
		
		
		Tensor state = null;
		String s = request.getParameter("state");
		if(s!=null){
			state = converter.fromJson(parser.parse(s).getAsJsonObject());		
		} else {
			String sz = request.getParameter("stateSize");
			if(sz == null){
				System.out.println("You should provide either state or stateSize");
				return;
			}
			state = new Tensor(Integer.parseInt(sz));
			state.fill(0.0f);
		}
		
		try {
			Tensor action = null;
			Tensor observation = null;
	
			Sequence<ExperiencePoolSample> xp = dataset.getSequence(sequence);
			if(index > 0){
				ExperiencePoolSample prev = xp.get(index-1);
				ExperiencePoolSample current = xp.get(index);
				action = prev.getAction();
				observation = current.getState();
			} else {
				ExperiencePoolSample current = xp.get(0);
				action = current.getAction().clone();
				action.fill(0.0f);
				observation = current.getState();
			}
			
			Tensor prior = null;
			if(predictor == null){
				prior = state.clone();
				prior.fill(0.0f);
				prior.narrow(0, prior.size()/2, prior.size()/2).fill(1.0f);;
			} else if(predictor != null){
				if(index==0){
					state.fill(0.0f);
					action.fill(0.0f);
				}
				UUID[] pins = predictor.getModuleIds("State","Action");
				UUID[] pouts = predictor.getModuleIds("Output");
				prior = predictor.forward(pins, pouts, new Tensor[]{state, action}).getValue().tensor;
			}
			
			UUID[] eins = encoder.getModuleIds("State","Action","Observation");
			UUID[] eouts = encoder.getModuleIds("Output");
			Tensor posterior = encoder.forward(eins, eouts, new Tensor[]{state, action, observation}).getValue().tensor;
	
			Tensor stateSample = sampleState(posterior);
			
			Tensor reconstruction = null;
			if(decoder != null){
				reconstruction = decoder.forward(stateSample);
				reconstruction = reconstruction.narrow(0, 0, reconstruction.size()/2);
			}
		
			JsonObject result = new JsonObject();
			result.add("state", converter.toJson(state));
			result.add("action", converter.toJson(action));
			result.add("observation", converter.toJson(observation));
			
			if(prior != null)
				result.add("prior", converter.toJson(prior));
			
			result.add("posterior", converter.toJson(posterior));
			result.add("sample", converter.toJson(stateSample));
			
			if(reconstruction != null)
				result.add("reconstruction", converter.toJson(reconstruction));
			
			response.getWriter().println(result);
			response.getWriter().flush();
		
		} catch(Exception e){
			e.printStackTrace();
		}
	}
	

	
	private Tensor sampleState( Tensor stateDistribution) {
		int size = stateDistribution.size()/2;
		Tensor means = stateDistribution.narrow(0, 0, size);
		Tensor stdevs = stateDistribution.narrow(0, size, size);
		
		Tensor random = new Tensor(means.size());
		random.randn();
		
		Tensor sample = new Tensor(means.size());
		
		TensorOps.cmul(sample, random, stdevs);
		TensorOps.add(sample, sample, means);
		return sample;
	}
	
}